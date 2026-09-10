package com.textorigin.service;

import com.textorigin.exception.QuotaExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controla el número de análisis que puede lanzar cada conexión para evitar abusos y
 * contener el gasto de tokens de DeepSeek.
 *
 * <p>El límite es diario y por IP ({@code textorigin.quota.max-per-ip-per-day}, 10 por
 * defecto): el contador vive en un {@link ConcurrentHashMap} en memoria, indexado por la IP
 * real del cliente. Una tarea programada a las 3:00 elimina las entradas de días anteriores.</p>
 *
 * <p>El ciclo de consumo es deliberadamente conservador: {@link #checkQuota(HttpServletRequest)}
 * se ejecuta <em>antes</em> de llamar a DeepSeek para no gastar tokens en vano, pero el
 * consumo solo se registra con {@link #registerConsumption(String)} cuando el análisis ha
 * terminado correctamente. Un análisis fallido no descuenta cuota.</p>
 *
 * <p>La verificación y el registro no son una operación atómica: dos peticiones simultáneas
 * de la misma IP podrían pasar ambas la comprobación antes de que ninguna registre su
 * consumo. Es una aproximación aceptable para un límite anti-abuso de este tipo.</p>
 */
@Slf4j
@Service
public class QuotaService {

    /** Correo de contacto usado si no se configura {@code textorigin.contact-email}. */
    private static final String DEFAULT_CONTACT_EMAIL = "jgrateron@gmail.com";

    /**
     * Cabeceras que pueden contener la IP real cuando hay un proxy inverso delante.
     * Se consultan en este orden; la primera que traiga un valor válido gana.
     */
    private static final List<String> IP_HEADERS = List.of(
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP");

    private final int maxPerIpPerDay;
    private final String contactEmail;

    /** Contadores por IP, con la fecha del día al que corresponden. */
    private final ConcurrentHashMap<String, IpQuota> ipQuotas = new ConcurrentHashMap<>();

    public QuotaService(@Value("${textorigin.quota.max-per-ip-per-day:10}") int maxPerIpPerDay,
                        @Value("${textorigin.contact-email:" + DEFAULT_CONTACT_EMAIL + "}") String contactEmail) {
        this.maxPerIpPerDay = maxPerIpPerDay;
        this.contactEmail = contactEmail;
        log.info("Sistema de cuotas activo: {} análisis por IP y día (contacto: {})",
                maxPerIpPerDay, contactEmail);
    }

    /**
     * Correo de contacto mostrado al agotar la cuota, en los errores y en el informe PDF.
     *
     * @return el correo configurado en {@code textorigin.contact-email}
     */
    public String getContactEmail() {
        return contactEmail;
    }

    /** Contador de una IP para un día concreto. */
    private record IpQuota(LocalDate date, AtomicInteger count) {
    }

    /**
     * Estado de la cuota en un momento dado, listo para pintarlo en la interfaz.
     */
    @Getter
    public static class QuotaStatus {

        private final int ipUsed;
        private final int ipLimit;
        private final int ipRemaining;

        QuotaStatus(int ipUsed, int ipLimit) {
            this.ipUsed = ipUsed;
            this.ipLimit = ipLimit;
            this.ipRemaining = Math.max(0, ipLimit - ipUsed);
        }

        /** Indica si ya no se puede lanzar ningún análisis. */
        public boolean isExhausted() {
            return ipRemaining <= 0;
        }

        /** Porcentaje de cuota diaria todavía disponible (0-100). */
        public int getIpPercent() {
            return ipLimit == 0 ? 0 : (int) Math.round(ipRemaining * 100.0 / ipLimit);
        }

        /** Clase CSS de la barra: se vuelve roja al agotarse y ámbar con un solo análisis. */
        public String getIpBarClass() {
            if (ipRemaining <= 0) {
                return "quota-bar-fill quota-bar-empty";
            }
            return ipRemaining == 1 ? "quota-bar-fill quota-bar-low" : "quota-bar-fill";
        }
    }

    // ==================================================================
    // Consulta y verificación
    // ==================================================================

    /**
     * Devuelve el estado actual de la cuota del visitante.
     *
     * @param request petición HTTP en curso
     * @return el estado de la cuota, nunca {@code null}
     */
    public QuotaStatus getStatus(HttpServletRequest request) {
        return new QuotaStatus(currentIpCount(getClientIp(request)), maxPerIpPerDay);
    }

    /**
     * Comprueba que el visitante puede lanzar un análisis. Debe invocarse antes de llamar a
     * DeepSeek.
     *
     * @param request petición HTTP en curso
     * @throws QuotaExceededException si se ha agotado la cuota diaria de la conexión
     */
    public void checkQuota(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        int ipUsed = currentIpCount(clientIp);
        if (ipUsed >= maxPerIpPerDay) {
            log.warn("Cuota denegada: ip={} consumo={}/{}", clientIp, ipUsed, maxPerIpPerDay);
            throw new QuotaExceededException(maxPerIpPerDay, ipUsed,
                    "Límite de análisis diarios por IP alcanzado");
        }

        log.debug("Cuota disponible: ip={} ({}/{})", clientIp, ipUsed, maxPerIpPerDay);
    }

    // ==================================================================
    // Registro de consumo
    // ==================================================================

    /**
     * Registra un análisis completado con éxito. Se llama solo cuando el análisis ha
     * terminado correctamente y puede invocarse desde el hilo asíncrono: solo usa la IP
     * capturada durante la petición, sin tocar la {@code HttpServletRequest}.
     *
     * @param clientIp IP del cliente, capturada durante la petición
     */
    public void registerConsumption(String clientIp) {
        IpQuota quota = ipQuotas.compute(clientIp, (ip, existing) ->
                (existing == null || !existing.date().equals(LocalDate.now()))
                        ? new IpQuota(LocalDate.now(), new AtomicInteger())
                        : existing);
        int ipUsed = quota.count().incrementAndGet();

        log.info("Consumo registrado: ip={} {}/{}", clientIp, ipUsed, maxPerIpPerDay);
    }

    // ==================================================================
    // IP real del cliente
    // ==================================================================

    /**
     * Obtiene la IP del cliente teniendo en cuenta las cabeceras que añaden los proxies
     * inversos. Si no hay ninguna cabecera utilizable, usa {@code getRemoteAddr()}.
     *
     * @param request petición HTTP
     * @return la IP del cliente, o {@code "desconocida"} si no se puede determinar
     */
    public String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (isUsableHeader(value)) {
                String ip = firstIp(value);
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        String remoteAddress = request.getRemoteAddr();
        return (remoteAddress == null || remoteAddress.isBlank()) ? "desconocida" : remoteAddress;
    }

    private boolean isUsableHeader(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value.trim());
    }

    /** Toma la primera IP de una cabecera que puede contener una cadena de proxies. */
    private String firstIp(String headerValue) {
        String value = headerValue.trim();
        int comma = value.indexOf(',');
        if (comma > 0) {
            value = value.substring(0, comma).trim();
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        // Sólo se elimina el puerto cuando la dirección es IPv4 (un único ':').
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            return value.substring(0, colon);
        }
        return value;
    }

    // ==================================================================
    // Limpieza programada
    // ==================================================================

    /**
     * Elimina las entradas de días anteriores. Se ejecuta todos los días a las 3:00.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupIpQuotas() {
        LocalDate today = LocalDate.now();
        int before = ipQuotas.size();
        ipQuotas.entrySet().removeIf(entry -> entry.getValue().date().isBefore(today));
        int removed = before - ipQuotas.size();
        log.info("Limpieza programada de cuotas por IP: {} entradas eliminadas, {} activas",
                removed, ipQuotas.size());
    }

    private int currentIpCount(String clientIp) {
        IpQuota quota = ipQuotas.get(clientIp);
        if (quota == null || !quota.date().equals(LocalDate.now())) {
            return 0;
        }
        return quota.count().get();
    }

    /** Número de IPs con contador activo. Útil para diagnóstico y pruebas. */
    public int trackedIpCount() {
        return ipQuotas.size();
    }
}

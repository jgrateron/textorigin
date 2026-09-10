package com.textorigin.service;

import com.textorigin.exception.QuotaExceededException;
import com.textorigin.exception.QuotaExceededException.QuotaType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
 * Controla el número de análisis que puede lanzar cada usuario para evitar abusos y
 * contener el gasto de tokens de DeepSeek.
 *
 * <p>Funciona con dos límites independientes y complementarios:</p>
 * <ul>
 *   <li><strong>Por sesión HTTP</strong> ({@code textorigin.quota.max-per-session}, 3 por
 *       defecto): el contador vive como atributo de la {@link HttpSession}, de modo que se
 *       reinicia solo cuando la sesión caduca por inactividad (30 minutos).</li>
 *   <li><strong>Por IP y día</strong> ({@code textorigin.quota.max-per-ip-per-day}, 10 por
 *       defecto): el contador vive en un {@link ConcurrentHashMap} en memoria, indexado por
 *       la IP real del cliente. Una tarea programada a las 3:00 elimina las entradas de
 *       días anteriores.</li>
 * </ul>
 *
 * <p>El ciclo de consumo es deliberadamente conservador: {@link #checkQuota(HttpServletRequest)}
 * se ejecuta <em>antes</em> de llamar a DeepSeek para no gastar tokens en vano, pero el
 * consumo solo se registra con {@link #registerConsumption} cuando el análisis ha terminado
 * correctamente. Un análisis fallido no descuenta cuota.</p>
 *
 * <p>La verificación y el registro no son una operación atómica: dos peticiones simultáneas
 * de la misma sesión podrían pasar ambas la comprobación antes de que ninguna registre su
 * consumo. Es una aproximación aceptable para un límite anti-abuso de este tipo; la
 * alternativa (bloquear la sesión durante todo el análisis) penalizaría mucho más al usuario.</p>
 */
@Slf4j
@Service
public class QuotaService {

    /** Correo de contacto usado si no se configura {@code textorigin.contact-email}. */
    private static final String DEFAULT_CONTACT_EMAIL = "jgrateron@gmail.com";

    /** Atributo de sesión donde se guarda el contador. */
    private static final String SESSION_COUNTER_ATTRIBUTE = "textorigin.quota.session.counter";

    /**
     * Cabeceras que pueden contener la IP real cuando hay un proxy inverso delante.
     * Se consultan en este orden; la primera que traiga un valor válido gana.
     */
    private static final List<String> IP_HEADERS = List.of(
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP");

    private final int maxPerSession;
    private final int maxPerIpPerDay;
    private final String contactEmail;

    /** Contadores por IP, con la fecha del día al que corresponden. */
    private final ConcurrentHashMap<String, IpQuota> ipQuotas = new ConcurrentHashMap<>();

    public QuotaService(@Value("${textorigin.quota.max-per-session:3}") int maxPerSession,
                        @Value("${textorigin.quota.max-per-ip-per-day:10}") int maxPerIpPerDay,
                        @Value("${textorigin.contact-email:" + DEFAULT_CONTACT_EMAIL + "}") String contactEmail) {
        this.maxPerSession = maxPerSession;
        this.maxPerIpPerDay = maxPerIpPerDay;
        this.contactEmail = contactEmail;
        log.info("Sistema de cuotas activo: {} análisis por sesión, {} por IP y día (contacto: {})",
                maxPerSession, maxPerIpPerDay, contactEmail);
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

        private final int sessionUsed;
        private final int sessionLimit;
        private final int sessionRemaining;
        private final int ipUsed;
        private final int ipLimit;
        private final int ipRemaining;

        QuotaStatus(int sessionUsed, int sessionLimit, int ipUsed, int ipLimit) {
            this.sessionUsed = sessionUsed;
            this.sessionLimit = sessionLimit;
            this.sessionRemaining = Math.max(0, sessionLimit - sessionUsed);
            this.ipUsed = ipUsed;
            this.ipLimit = ipLimit;
            this.ipRemaining = Math.max(0, ipLimit - ipUsed);
        }

        /** Indica si ya no se puede lanzar ningún análisis. */
        public boolean isExhausted() {
            return sessionRemaining <= 0 || ipRemaining <= 0;
        }

        /** Motivo del agotamiento, para el mensaje de la interfaz. */
        public String getExhaustedReason() {
            if (sessionRemaining <= 0) {
                return "SESSION";
            }
            return ipRemaining <= 0 ? "IP" : "";
        }

        /** Porcentaje de cuota de sesión todavía disponible (0-100). */
        public int getSessionPercent() {
            return sessionLimit == 0 ? 0 : (int) Math.round(sessionRemaining * 100.0 / sessionLimit);
        }

        /** Porcentaje de cuota por IP todavía disponible (0-100). */
        public int getIpPercent() {
            return ipLimit == 0 ? 0 : (int) Math.round(ipRemaining * 100.0 / ipLimit);
        }

        /** Clase CSS de la barra de sesión: se vuelve roja al agotarse. */
        public String getSessionBarClass() {
            if (sessionRemaining <= 0) {
                return "quota-bar-fill quota-bar-empty";
            }
            return sessionRemaining == 1 ? "quota-bar-fill quota-bar-low" : "quota-bar-fill";
        }

        /** Clase CSS de la barra por IP. */
        public String getIpBarClass() {
            return ipRemaining <= 0 ? "quota-bar-fill quota-bar-empty" : "quota-bar-fill";
        }
    }

    // ==================================================================
    // Consulta y verificación
    // ==================================================================

    /**
     * Devuelve el estado actual de la cuota del visitante. Crea la sesión si aún no existe,
     * porque el contador de sesión vive en ella.
     *
     * @param request petición HTTP en curso
     * @return el estado de la cuota, nunca {@code null}
     */
    public QuotaStatus getStatus(HttpServletRequest request) {
        int sessionUsed = sessionCounter(request.getSession(true)).get();
        int ipUsed = currentIpCount(getClientIp(request));
        return new QuotaStatus(sessionUsed, maxPerSession, ipUsed, maxPerIpPerDay);
    }

    /**
     * Comprueba que el visitante puede lanzar un análisis. Debe invocarse antes de llamar a
     * DeepSeek.
     *
     * @param request petición HTTP en curso
     * @throws QuotaExceededException si se ha agotado la cuota de sesión o la de IP
     */
    public void checkQuota(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        int sessionUsed = sessionCounter(session).get();
        if (sessionUsed >= maxPerSession) {
            log.warn("Cuota denegada (sesión): sesión={} consumo={}/{}", session.getId(),
                    sessionUsed, maxPerSession);
            throw new QuotaExceededException(QuotaType.SESSION, maxPerSession, sessionUsed,
                    "Límite de análisis por sesión alcanzado");
        }

        String clientIp = getClientIp(request);
        int ipUsed = currentIpCount(clientIp);
        if (ipUsed >= maxPerIpPerDay) {
            log.warn("Cuota denegada (IP): ip={} consumo={}/{}", clientIp, ipUsed, maxPerIpPerDay);
            throw new QuotaExceededException(QuotaType.IP, maxPerIpPerDay, ipUsed,
                    "Límite de análisis diarios por IP alcanzado");
        }

        log.debug("Cuota disponible: sesión={}/{} ip={} ({}/{})",
                sessionUsed, maxPerSession, clientIp, ipUsed, maxPerIpPerDay);
    }

    // ==================================================================
    // Registro de consumo
    // ==================================================================

    /**
     * Obtiene (creando si hace falta) el contador asociado a una sesión.
     *
     * <p>Se expone para que el controlador pueda capturar el contador antes de lanzar el
     * análisis asíncrono y registrar el consumo desde el hilo de fondo sin volver a tocar
     * la {@link HttpSession}, que puede haber caducado para entonces.</p>
     *
     * @param session sesión HTTP
     * @return contador de análisis de esa sesión
     */
    public AtomicInteger sessionCounter(HttpSession session) {
        Object existing = session.getAttribute(SESSION_COUNTER_ATTRIBUTE);
        if (existing instanceof AtomicInteger counter) {
            return counter;
        }
        AtomicInteger counter = new AtomicInteger();
        session.setAttribute(SESSION_COUNTER_ATTRIBUTE, counter);
        return counter;
    }

    /**
     * Registra un análisis completado con éxito sobre la sesión indicada.
     *
     * @param session   sesión HTTP
     * @param clientIp  IP del cliente
     */
    public void registerConsumption(HttpSession session, String clientIp) {
        registerConsumption(sessionCounter(session), clientIp);
    }

    /**
     * Registra un análisis completado con éxito. Se llama solo cuando el análisis ha
     * terminado correctamente, y puede invocarse desde el hilo asíncrono.
     *
     * @param sessionCounter contador de la sesión, capturado durante la petición
     * @param clientIp       IP del cliente, capturada durante la petición
     */
    public void registerConsumption(AtomicInteger sessionCounter, String clientIp) {
        int sessionUsed = sessionCounter.incrementAndGet();
        IpQuota quota = ipQuotas.compute(clientIp, (ip, existing) ->
                (existing == null || !existing.date().equals(LocalDate.now()))
                        ? new IpQuota(LocalDate.now(), new AtomicInteger())
                        : existing);
        int ipUsed = quota.count().incrementAndGet();

        log.info("Consumo registrado: ip={} sesión={}/{} ip={}/{}",
                clientIp, sessionUsed, maxPerSession, ipUsed, maxPerIpPerDay);
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

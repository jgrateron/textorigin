/**
 * TextOrigin · Estado del formulario de análisis
 *
 * Mantiene desactivado el botón «Analizar documento» mientras hay un análisis en curso y
 * también cuando ya se están mostrando sus resultados completados: el botón solo vuelve a
 * estar activo al regresar a la página con «Analizar otro documento» (o al recargarla), de
 * modo que no se lance un segundo análisis sobre unos resultados que siguen en pantalla.
 *
 * Un análisis fallido sí reactiva el botón: no hay banner con «Analizar otro documento» y lo
 * razonable es permitir reintentar sin recargar.
 *
 * El estado se deduce del DOM al terminar cada petición HTMX (htmx:afterRequest se dispara
 * después del intercambio y después de que htmx reactive los elementos de hx-disabled-elt,
 * así que este ajuste no se pisa con el suyo), y cubre por igual el envío inicial, los
 * sondeos del progreso, la llegada de los resultados y los errores.
 */
(function () {
    'use strict';

    /** Desactiva el botón mientras el análisis esté en curso o sus resultados en pantalla. */
    function syncSubmitButton() {
        var form = document.getElementById('analysis-form');
        var button = form && form.querySelector('button[type="submit"]');
        if (!button) {
            return;
        }
        var analyzing = document.querySelector('.progress-panel') !== null;
        var resultsShown = document.querySelector('.results__banner') !== null;
        button.disabled = analyzing || resultsShown;
    }

    document.addEventListener('htmx:afterRequest', syncSubmitButton);
})();

/**
 * TextOrigin · Estado del formulario de análisis
 *
 * Mantiene desactivado el botón «Analizar documento» mientras hay un análisis en curso.
 * El POST a /analysis/analyze responde de inmediato con el panel de progreso, de modo que
 * hx-disabled-elt solo cubre el envío: el análisis continúa en segundo plano y el botón debe
 * seguir desactivado hasta que los resultados sustituyan al progreso.
 *
 * El estado se deduce del DOM (¿sigue el panel de progreso en la página?) al terminar cada
 * petición HTMX, así que cubre por igual el envío inicial, los sondeos del progreso, la
 * llegada de los resultados y los errores.
 */
(function () {
    'use strict';

    /** Desactiva el botón de envío mientras el panel de progreso siga en la página. */
    function syncSubmitButton() {
        var form = document.getElementById('analysis-form');
        var button = form && form.querySelector('button[type="submit"]');
        if (!button) {
            return;
        }
        button.disabled = document.querySelector('.progress-panel') !== null;
    }

    // htmx:afterRequest se dispara al terminar cualquier petición HTMX —también los sondeos
    // del progreso— y después de que htmx reactive los elementos con hx-disabled-elt, por lo
    // que este ajuste no se pisa con el suyo.
    document.addEventListener('htmx:afterRequest', syncSubmitButton);
})();

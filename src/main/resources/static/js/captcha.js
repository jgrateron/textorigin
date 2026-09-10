/*
 * Verificación anti-bots (Cloudflare Turnstile) del formulario de análisis.
 *
 * La comprobación real la hace el servidor (CaptchaService) al recibir el POST; aquí solo se
 * evita el viaje en balde y se renueva el token:
 *
 *   · Antes de enviar: si el widget está en la página y todavía no hay token, se cancela el
 *     envío y se muestra el aviso #captcha-error.
 *   · Después de cada envío del formulario: el token es de un solo uso, así que se pide uno
 *     nuevo con turnstile.reset(). El reinicio se limita a las peticiones del formulario
 *     porque el sondeo del estado del análisis también dispara htmx:afterRequest y no debe
 *     tocar el widget.
 */
(function () {
    'use strict';

    var FORM_ID = 'analysis-form';
    var TOKEN_FIELD = 'cf-turnstile-response';
    var WIDGET_SELECTOR = '.cf-turnstile';

    /** Devuelve el formulario de análisis solo si el evento procede de él. */
    function analysisForm(event) {
        var elt = event.detail && event.detail.elt;
        return elt && elt.id === FORM_ID ? elt : null;
    }

    /** Campo oculto que Turnstile inyecta en el formulario al resolver el reto. */
    function tokenOf(form) {
        return form.querySelector('input[name="' + TOKEN_FIELD + '"]');
    }

    function showError(form, visible) {
        var error = form.querySelector('#captcha-error');
        if (error) {
            error.hidden = !visible;
        }
    }

    document.addEventListener('htmx:beforeRequest', function (event) {
        var form = analysisForm(event);
        if (!form || !form.querySelector(WIDGET_SELECTOR)) {
            return; // Sin CAPTCHA configurado no hay nada que comprobar.
        }

        var solved = tokenOf(form);
        if (solved && solved.value) {
            showError(form, false);
            return;
        }
        event.preventDefault();
        showError(form, true);
    });

    document.addEventListener('htmx:afterRequest', function (event) {
        var form = analysisForm(event);
        if (!form || !form.querySelector(WIDGET_SELECTOR)) {
            return;
        }
        // El token acaba de usarse: el widget debe entregar uno nuevo para el próximo envío.
        if (window.turnstile) {
            window.turnstile.reset();
        }
    });
})();

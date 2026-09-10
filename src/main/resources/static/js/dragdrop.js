/**
 * TextOrigin · Arrastrar y soltar archivos
 *
 * Gestiona la zona de carga del formulario: arrastrar un archivo sobre ella, seleccionarlo
 * con el explorador, validarlo en el cliente (extensión y tamaño) y reflejarlo en la interfaz.
 *
 * Compatibilidad: Chrome, Firefox y Safari modernos. El archivo se inyecta en el input real
 * mediante DataTransfer, que es la única forma de que HTMX lo incluya en el FormData de la
 * petición. Si el navegador no permite construir un DataTransfer (versiones antiguas de
 * Safari), se avisa y se pide usar el botón de selección.
 */
(function () {
    'use strict';

    /** 10 MB, el mismo límite que aplica el servidor. */
    var MAX_FILE_SIZE = 10 * 1024 * 1024;

    /** Extensiones admitidas. */
    var ALLOWED_EXTENSIONS = ['pdf', 'docx', 'txt'];

    /** Tipos MIME que Safari y Firefox asignan de forma fiable. */
    var ALLOWED_MIME = [
        'application/pdf',
        'text/plain',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    ];

    var dropzone = null;
    var input = null;
    var preview = null;
    var previewName = null;
    var previewSize = null;
    var errorBox = null;
    var initialized = false;

    document.addEventListener('DOMContentLoaded', function () {
        initDropzone();
    });

    // La zona de carga vive fuera de los puntos de intercambio de HTMX, pero se reinicializa
    // tras cada swap para que siga funcionando en cualquier disposición futura.
    document.body.addEventListener('htmx:afterSwap', function () {
        if (!initialized) {
            initDropzone();
        }
    });

    function initDropzone() {
        dropzone = document.getElementById('dropzone');
        if (!dropzone) {
            return;
        }

        input = document.getElementById('file-input');
        preview = document.getElementById('file-preview');
        previewName = document.getElementById('file-preview-name');
        previewSize = document.getElementById('file-preview-size');
        errorBox = document.getElementById('dropzone-error');

        var browseButton = document.getElementById('browse-button');
        var clearButton = document.getElementById('file-clear');
        var textarea = document.getElementById('text-input');

        // Clic en cualquier punto de la zona abre el explorador de archivos.
        dropzone.addEventListener('click', function (event) {
            if (event.target === clearButton || (clearButton && clearButton.contains(event.target))) {
                return;
            }
            if (event.target === browseButton || input === event.target) {
                return;
            }
            input.click();
        });

        if (browseButton) {
            browseButton.addEventListener('click', function (event) {
                event.preventDefault();
                event.stopPropagation();
                input.click();
            });
        }

        input.addEventListener('change', function () {
            var file = input.files && input.files.length ? input.files[0] : null;
            if (file) {
                validateAndShow(file);
            } else {
                clearFile();
            }
        });

        if (clearButton) {
            clearButton.addEventListener('click', function (event) {
                event.preventDefault();
                event.stopPropagation();
                clearFile();
            });
        }

        ['dragenter', 'dragover'].forEach(function (eventName) {
            dropzone.addEventListener(eventName, function (event) {
                event.preventDefault();
                event.stopPropagation();
                dropzone.classList.add('is-dragover');
            });
        });

        ['dragleave', 'drop'].forEach(function (eventName) {
            dropzone.addEventListener(eventName, function (event) {
                event.preventDefault();
                event.stopPropagation();
                if (eventName === 'dragleave' && dropzone.contains(event.relatedTarget)) {
                    return;
                }
                dropzone.classList.remove('is-dragover');
            });
        });

        dropzone.addEventListener('drop', function (event) {
            var files = event.dataTransfer ? event.dataTransfer.files : null;
            if (!files || !files.length) {
                return;
            }
            var file = files[0];
            if (!assignToInput(file)) {
                return;
            }
            validateAndShow(file);
        });

        // Arrastrar sobre la ventana no debe abrir el archivo en el navegador.
        ['dragover', 'drop'].forEach(function (eventName) {
            window.addEventListener(eventName, function (event) {
                if (!dropzone.contains(event.target)) {
                    event.preventDefault();
                }
            });
        });

        if (textarea) {
            textarea.addEventListener('input', function () {
                if (input.files && input.files.length) {
                    showError('Se analizará el archivo seleccionado. Quítalo si prefieres analizar el texto pegado.');
                } else {
                    hideError();
                }
            });
        }

        initialized = true;
    }

    /**
     * Coloca el archivo arrastrado en el input real para que el formulario lo envíe.
     * Devuelve false si el navegador no permite hacerlo.
     */
    function assignToInput(file) {
        try {
            var transfer = new DataTransfer();
            transfer.items.add(file);
            input.files = transfer.files;
            return true;
        } catch (error) {
            // Safari < 14.1 y navegadores antiguos no permiten asignar 'files'.
            input.value = '';
            showError('Tu navegador no permite arrastrar archivos. Usa el botón «búsquelo en tu equipo».');
            return false;
        }
    }

    /** Valida el archivo y actualiza la interfaz. */
    function validateAndShow(file) {
        var extension = file.name.indexOf('.') >= 0
            ? file.name.split('.').pop().toLowerCase()
            : '';

        if (ALLOWED_EXTENSIONS.indexOf(extension) === -1 && ALLOWED_MIME.indexOf(file.type) === -1) {
            input.value = '';
            showError('Formato no soportado. Sube un archivo PDF, DOCX o TXT.');
            return;
        }

        if (file.size > MAX_FILE_SIZE) {
            input.value = '';
            showError('El archivo ocupa ' + formatSize(file.size) + ' y el máximo permitido es 10 MB.');
            return;
        }

        if (file.size === 0) {
            input.value = '';
            showError('El archivo está vacío.');
            return;
        }

        hideError();
        showFile(file);
    }

    /** Muestra el archivo seleccionado en la zona de carga. */
    function showFile(file) {
        if (previewName) {
            previewName.textContent = file.name;
        }
        if (previewSize) {
            previewSize.textContent = formatSize(file.size) + ' · ' + file.name.split('.').pop().toUpperCase();
        }
        if (preview) {
            preview.hidden = false;
        }

        var inner = document.getElementById('dropzone-inner');
        if (inner) {
            inner.hidden = true;
        }
        if (dropzone) {
            dropzone.classList.add('has-file');
        }
    }

    /** Quita el archivo seleccionado y restaura la vista inicial. */
    function clearFile() {
        if (input) {
            input.value = '';
        }
        if (preview) {
            preview.hidden = true;
        }

        var inner = document.getElementById('dropzone-inner');
        if (inner) {
            inner.hidden = false;
        }
        if (dropzone) {
            dropzone.classList.remove('has-file');
        }
        hideError();
    }

    function showError(message) {
        if (!errorBox) {
            return;
        }
        errorBox.textContent = message;
        errorBox.hidden = false;
    }

    function hideError() {
        if (errorBox) {
            errorBox.hidden = true;
        }
    }

    /** Tamaño legible: 2,4 MB / 812 KB. */
    function formatSize(bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)).toFixed(1).replace('.', ',') + ' MB';
        }
        return Math.max(1, Math.round(bytes / 1024)) + ' KB';
    }

    // Reinicia la zona de carga y los indicadores cuando se completa un envío, para que el
    // formulario quede listo si el usuario quiere analizar otro documento.
    document.body.addEventListener('htmx:afterRequest', function (event) {
        var form = document.getElementById('analysis-form');
        if (form && event.detail && event.detail.elt === form && event.detail.successful) {
            var textarea = document.getElementById('text-input');
            if (textarea) {
                textarea.value = '';
            }
            clearFile();
        }
    });
})();

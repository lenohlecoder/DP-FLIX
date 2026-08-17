package com.djamylova.tvflix

/**
 * Script JS injecté pour faire croire aux sites qu’ils sont sur un desktop
 * (pas de tactile), afin d’éviter le basculement en version mobile.
 *
 * Étape 7 – Masquage des capacités tactiles.
 */
object DesktopSpoofJs {

    /**
     * Script à injecter le plus tôt possible (idéalement avant le parsing des scripts de la page).
     * Couvre les détections les plus courantes :
     * - navigator.maxTouchPoints
     * - 'ontouchstart' in window
     * - TouchEvent / DocumentTouch
     * - matchMedia (pointer: coarse) / (hover: none)
     */
    val SCRIPT: String = """
(function() {
    if (window.__tvflixDesktopSpoofApplied) return;
    window.__tvflixDesktopSpoofApplied = true;

    try {
        // 1. maxTouchPoints = 0
        Object.defineProperty(navigator, 'maxTouchPoints', {
            get: function() { return 0; },
            configurable: true
        });
    } catch (e) {}

    try {
        // Certains sites lisent aussi msMaxTouchPoints
        Object.defineProperty(navigator, 'msMaxTouchPoints', {
            get: function() { return 0; },
            configurable: true
        });
    } catch (e) {}

    try {
        // 2. Supprimer les handlers touch sur window / document
        //    et faire échouer le test « 'ontouchstart' in window »
        var touchProps = ['ontouchstart', 'ontouchmove', 'ontouchend', 'ontouchcancel'];
        touchProps.forEach(function(prop) {
            try {
                if (prop in window) {
                    Object.defineProperty(window, prop, {
                        get: function() { return undefined; },
                        set: function() {},
                        configurable: true
                    });
                }
            } catch (e) {}
            try {
                if (document && prop in document) {
                    Object.defineProperty(document, prop, {
                        get: function() { return undefined; },
                        set: function() {},
                        configurable: true
                    });
                }
            } catch (e) {}
        });
    } catch (e) {}

    try {
        // 3. Masquer TouchEvent / DocumentTouch
        if (typeof window.TouchEvent !== 'undefined') {
            window.TouchEvent = undefined;
        }
        if (typeof window.DocumentTouch !== 'undefined') {
            window.DocumentTouch = undefined;
        }
    } catch (e) {}

    try {
        // 4. Spoof matchMedia pour pointer / hover
        //    (pointer: fine) + (hover: hover) = desktop
        var originalMatchMedia = window.matchMedia;
        if (typeof originalMatchMedia === 'function') {
            window.matchMedia = function(query) {
                var q = (query || '').toLowerCase();
                // pointer: coarse → false, pointer: fine → true
                if (q.indexOf('pointer: coarse') !== -1 || q.indexOf('pointer:coarse') !== -1) {
                    return { matches: false, media: query, onchange: null,
                             addListener: function(){}, removeListener: function(){},
                             addEventListener: function(){}, removeEventListener: function(){},
                             dispatchEvent: function(){ return false; } };
                }
                if (q.indexOf('pointer: fine') !== -1 || q.indexOf('pointer:fine') !== -1) {
                    return { matches: true, media: query, onchange: null,
                             addListener: function(){}, removeListener: function(){},
                             addEventListener: function(){}, removeEventListener: function(){},
                             dispatchEvent: function(){ return false; } };
                }
                // hover: none → false, hover: hover → true
                if (q.indexOf('hover: none') !== -1 || q.indexOf('hover:none') !== -1) {
                    return { matches: false, media: query, onchange: null,
                             addListener: function(){}, removeListener: function(){},
                             addEventListener: function(){}, removeEventListener: function(){},
                             dispatchEvent: function(){ return false; } };
                }
                if (q.indexOf('hover: hover') !== -1 || q.indexOf('hover:hover') !== -1) {
                    return { matches: true, media: query, onchange: null,
                             addListener: function(){}, removeListener: function(){},
                             addEventListener: function(){}, removeEventListener: function(){},
                             dispatchEvent: function(){ return false; } };
                }
                return originalMatchMedia.call(window, query);
            };
        }
    } catch (e) {}

    try {
        // 5. Forcer un screen-like desktop (certains sites regardent la largeur)
        //    On ne touche pas à window.innerWidth pour ne pas casser le layout,
        //    le User-Agent + viewport suffisent en général.
    } catch (e) {}

})();
""".trimIndent()
}

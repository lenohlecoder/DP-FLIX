package com.djamylova.tvflix

/**
 * Script JS pour forcer une empreinte « desktop » côté page.
 * YouTube et d’autres sites regardent bien plus que le simple User-Agent.
 */
object DesktopSpoofJs {

    val SCRIPT: String = """
(function() {
    if (window.__tvflixDesktopSpoofApplied) return;
    window.__tvflixDesktopSpoofApplied = true;

    try {
        Object.defineProperty(navigator, 'maxTouchPoints', {
            get: function() { return 0; }, configurable: true
        });
    } catch (e) {}

    try {
        Object.defineProperty(navigator, 'msMaxTouchPoints', {
            get: function() { return 0; }, configurable: true
        });
    } catch (e) {}

    // Plateforme desktop
    try {
        Object.defineProperty(navigator, 'platform', {
            get: function() { return 'Win32'; }, configurable: true
        });
    } catch (e) {}

    try {
        Object.defineProperty(navigator, 'vendor', {
            get: function() { return 'Google Inc.'; }, configurable: true
        });
    } catch (e) {}

    // userAgentData (Client Hints JS) — YouTube s’en sert beaucoup
    try {
        var uad = {
            brands: [
                { brand: 'Chromium', version: '126' },
                { brand: 'Google Chrome', version: '126' },
                { brand: 'Not-A.Brand', version: '8' }
            ],
            mobile: false,
            platform: 'Windows',
            getHighEntropyValues: function(hints) {
                return Promise.resolve({
                    architecture: 'x86',
                    bitness: '64',
                    mobile: false,
                    model: '',
                    platform: 'Windows',
                    platformVersion: '15.0.0',
                    uaFullVersion: '126.0.0.0',
                    fullVersionList: [
                        { brand: 'Chromium', version: '126.0.0.0' },
                        { brand: 'Google Chrome', version: '126.0.0.0' },
                        { brand: 'Not-A.Brand', version: '8.0.0.0' }
                    ]
                });
            },
            toJSON: function() {
                return { brands: this.brands, mobile: false, platform: 'Windows' };
            }
        };
        Object.defineProperty(navigator, 'userAgentData', {
            get: function() { return uad; }, configurable: true
        });
    } catch (e) {}

    try {
        var touchProps = ['ontouchstart', 'ontouchmove', 'ontouchend', 'ontouchcancel'];
        touchProps.forEach(function(prop) {
            try {
                Object.defineProperty(window, prop, {
                    get: function() { return undefined; },
                    set: function() {}, configurable: true
                });
            } catch (e) {}
            try {
                if (document) {
                    Object.defineProperty(document, prop, {
                        get: function() { return undefined; },
                        set: function() {}, configurable: true
                    });
                }
            } catch (e) {}
        });
    } catch (e) {}

    try {
        if (typeof window.TouchEvent !== 'undefined') window.TouchEvent = undefined;
        if (typeof window.DocumentTouch !== 'undefined') window.DocumentTouch = undefined;
    } catch (e) {}

    try {
        var originalMatchMedia = window.matchMedia;
        if (typeof originalMatchMedia === 'function') {
            window.matchMedia = function(query) {
                var q = (query || '').toLowerCase().replace(/\s/g, '');
                function fake(matches) {
                    return {
                        matches: matches, media: query, onchange: null,
                        addListener: function(){}, removeListener: function(){},
                        addEventListener: function(){}, removeEventListener: function(){},
                        dispatchEvent: function(){ return false; }
                    };
                }
                if (q.indexOf('pointer:coarse') !== -1) return fake(false);
                if (q.indexOf('pointer:fine') !== -1) return fake(true);
                if (q.indexOf('hover:none') !== -1) return fake(false);
                if (q.indexOf('hover:hover') !== -1) return fake(true);
                // YouTube regarde parfois any-pointer / any-hover
                if (q.indexOf('any-pointer:coarse') !== -1) return fake(false);
                if (q.indexOf('any-pointer:fine') !== -1) return fake(true);
                if (q.indexOf('any-hover:none') !== -1) return fake(false);
                if (q.indexOf('any-hover:hover') !== -1) return fake(true);
                return originalMatchMedia.call(window, query);
            };
        }
    } catch (e) {}

})();
""".trimIndent()
}

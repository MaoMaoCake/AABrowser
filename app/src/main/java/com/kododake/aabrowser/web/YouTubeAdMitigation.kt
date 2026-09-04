package com.kododake.aabrowser.web

/**
 * Document-start mitigation for YouTube's first-party player ads. Network rules
 * alone cannot distinguish these from the requested video on googlevideo.com.
 */
object YouTubeAdMitigation {
    val allowedOrigins = setOf(
        "https://youtube.com", "https://*.youtube.com",
        "https://youtube-nocookie.com", "https://*.youtube-nocookie.com",
        "https://youtubekids.com", "https://*.youtubekids.com"
    )

    val script = """
        (() => {
          if (!/(^|\.)(youtube\.com|youtube-nocookie\.com|youtubekids\.com)${'$'}/i.test(location.hostname) || window.__aaYouTubeShields) return;
          window.__aaYouTubeShields = true;

          const sanitizePlayerResponse = value => {
            if (!value || typeof value !== 'object') return value;
            try {
              delete value.adPlacements;
              delete value.playerAds;
              delete value.adSlots;
              if (value.playerResponse && typeof value.playerResponse === 'object') {
                sanitizePlayerResponse(value.playerResponse);
              }
            } catch (_) {}
            return value;
          };

          // Player responses may arrive through fetch, XHR, or inline JSON. Hooking
          // JSON parsing at document start covers all three without proxying media.
          const originalParse = JSON.parse;
          JSON.parse = function(...args) {
            return sanitizePlayerResponse(originalParse.apply(this, args));
          };
          if (typeof Response !== 'undefined' && Response.prototype.json) {
            const originalResponseJson = Response.prototype.json;
            Response.prototype.json = function(...args) {
              return originalResponseJson.apply(this, args).then(sanitizePlayerResponse);
            };
          }

          let initialPlayerResponse;
          try {
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
              configurable: true,
              get: () => initialPlayerResponse,
              set: value => { initialPlayerResponse = sanitizePlayerResponse(value); }
            });
          } catch (_) {}

          const cosmeticCss = `
            .video-ads, .ytp-ad-module, .ytp-ad-overlay-container,
            ytd-ad-slot-renderer, ytd-display-ad-renderer,
            ytd-in-feed-ad-layout-renderer, ytd-promoted-video-renderer,
            ytd-promoted-sparkles-web-renderer, ytd-banner-promo-renderer,
            ytd-statement-banner-renderer, masthead-ad { display: none !important; }
          `;
          const installStyle = () => {
            if (document.getElementById('__aabrowser_youtube_filters')) return;
            const style = document.createElement('style');
            style.id = '__aabrowser_youtube_filters';
            style.textContent = cosmeticCss;
            (document.head || document.documentElement)?.appendChild(style);
          };

          let sweepQueued = false;
          const sweep = () => {
            sweepQueued = false;
            installStyle();
            document.querySelector(
              '.ytp-ad-skip-button-modern, .ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button'
            )?.click();
            const player = document.querySelector('.html5-video-player');
            const video = player?.querySelector('video');
            if (!video) return;
            if (player.classList.contains('ad-showing')) {
              if (!video.hasAttribute('data-aa-ad')) {
                video.setAttribute('data-aa-ad', video.muted ? 'muted' : 'audible');
              }
              video.muted = true;
              video.playbackRate = 16;
              if (Number.isFinite(video.duration) && video.duration > 0) {
                video.currentTime = Math.max(video.currentTime, video.duration - 0.05);
              }
            } else if (video.hasAttribute('data-aa-ad')) {
              const previousAudio = video.getAttribute('data-aa-ad');
              video.removeAttribute('data-aa-ad');
              video.playbackRate = 1;
              video.muted = previousAudio === 'muted';
            }
          };
          const queueSweep = () => {
            if (sweepQueued) return;
            sweepQueued = true;
            requestAnimationFrame(sweep);
          };
          const start = () => {
            sweep();
            new MutationObserver(queueSweep).observe(document.documentElement, {
              subtree: true, childList: true, attributes: true,
              attributeFilter: ['class', 'style']
            });
            document.addEventListener('timeupdate', queueSweep, true);
          };
          if (document.documentElement) start();
          else document.addEventListener('DOMContentLoaded', start, { once: true });
        })();
    """.trimIndent()

    fun appliesTo(url: String?): Boolean {
        val host = FilterEngine.hostOf(url) ?: return false
        return YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private val YOUTUBE_HOSTS = setOf("youtube.com", "youtube-nocookie.com", "youtubekids.com")
}

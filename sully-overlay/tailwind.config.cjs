// Offline precompiled Tailwind, replacing the source's CDN-only developer
// stylesheet. Mirrors the tokens used by the original launcher.
module.exports = {
  content: ['./index.html', './**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      fontFamily: { sans: ['var(--app-font)', 'sans-serif'] },
      colors: {
        primary: 'hsl(var(--primary-hue),var(--primary-sat),var(--primary-lightness))',
        'primary-focus': 'hsl(var(--primary-hue),var(--primary-sat),calc(var(--primary-lightness) - 10%))',
        'primary-light': 'hsl(var(--primary-hue),var(--primary-sat),92%)',
        surface: 'rgba(255,255,255,.75)',
        'surface-glass': 'rgba(255,255,255,.35)',
      },
      keyframes: {
        fadeIn: { '0%': { opacity: '0', transform: 'scale(.97) translateY(4px)' }, '100%': { opacity: '1', transform: 'scale(1) translateY(0)' } },
        slideUp: { from: { opacity: '0', transform: 'translateY(100%)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        slideDown: { from: { opacity: '0', transform: 'translateY(-100%)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        popIn: { from: { opacity: '0', transform: 'scale(.5)' }, to: { opacity: '1', transform: 'scale(1)' } },
        floatDrift: { from: { opacity: '.3', transform: 'translateY(0)' }, to: { opacity: '.6', transform: 'translateY(-30px)' } },
        shimmer: { from: { backgroundPosition: '-200% 0' }, to: { backgroundPosition: '200% 0' } },
      },
      animation: {
        'fade-in': 'fadeIn .35s cubic-bezier(.4,0,.2,1)',
        'slide-up': 'slideUp .35s cubic-bezier(.25,1,.5,1)',
        'slide-down': 'slideDown .35s cubic-bezier(.25,1,.5,1)',
        'pop-in': 'popIn .35s cubic-bezier(.175,.885,.32,1.275)',
        'float': 'floatDrift 4s ease-out infinite alternate',
        'shimmer': 'shimmer 2.5s ease-in-out infinite',
      },
    },
  },
};

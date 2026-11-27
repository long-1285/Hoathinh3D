/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        glass: {
          dark: 'rgba(15, 23, 42, 0.75)',
          light: 'rgba(255, 255, 255, 0.08)',
          border: 'rgba(255, 255, 255, 0.12)',
          accent: '#06b6d4'
        }
      },
      fontFamily: {
        sans: ['"Inter"', 'sans-serif'],
        heading: ['"Plus Jakarta Sans"', 'sans-serif']
      },
      backdropBlur: {
        '24': '24px'
      }
    },
  },
  plugins: [],
}

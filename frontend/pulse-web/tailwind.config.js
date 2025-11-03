import { fontFamily } from "tailwindcss/defaultTheme";

/** @type {import('tailwindcss').Config} */
export default {
  darkMode: "class",
  content: ["./public/index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "#050B16",
        surface: "rgba(15, 23, 42, 0.75)",
        accent: {
          cyan: "#22d3ee",
          magenta: "#e879f9"
        }
      },
      fontFamily: {
        sans: ["'Plus Jakarta Sans'", ...fontFamily.sans]
      },
      backgroundImage: {
        "radial-grid": "radial-gradient(circle at top, rgba(34,211,238,0.12), transparent 55%), radial-gradient(circle at bottom, rgba(232,121,249,0.08), transparent 60%)"
      },
      keyframes: {
        float: {
          "0%, 100%": { transform: "translateY(0px)" },
          "50%": { transform: "translateY(-4px)" }
        }
      },
      animation: {
        float: "float 6s ease-in-out infinite"
      },
      boxShadow: {
        glass: "0 10px 40px -20px rgba(14,165,233,0.45)"
      }
    }
  }
};

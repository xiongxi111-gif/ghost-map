import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base "./" keeps the static build deployable under a GitHub Pages sub-path.
export default defineConfig({ base: "./", plugins: [react()] });

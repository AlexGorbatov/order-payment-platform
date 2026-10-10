import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // A self-contained server for the container image (web/Dockerfile).
  output: "standalone",
  poweredByHeader: false,
  // Keep `next dev` from generating extra files inside the project.
  agentRules: false,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;

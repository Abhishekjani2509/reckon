import type { NextConfig } from "next";

// Proxy backend calls through the Next server so the browser stays same-origin and no
// CORS config is needed on the services. Hosts are overridable for non-local runs.
const COMMAND = process.env.COMMAND_URL ?? "http://localhost:8080";
const QUERY = process.env.QUERY_URL ?? "http://localhost:8082";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      { source: "/api/command/:path*", destination: `${COMMAND}/:path*` },
      { source: "/api/query/:path*", destination: `${QUERY}/:path*` },
    ];
  },
};

export default nextConfig;

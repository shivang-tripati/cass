import type { NextConfig } from "next";

// Origin of the Spring Boot API. The rewrite keeps browser traffic same-origin
// (the backend does not enable CORS) and lets cookies pass through untouched.
const apiOrigin = process.env.API_ORIGIN ?? "http://localhost:8081";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${apiOrigin}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;

import path from "node:path";
import { fileURLToPath } from "node:url";

const repoName = "QuPath_TimeStamp_extension";
const isGithubPages = process.env.GITHUB_PAGES === "true";
const siteRoot = path.dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "export",
  outputFileTracingRoot: siteRoot,
  trailingSlash: true,
  basePath: isGithubPages ? `/${repoName}` : "",
  assetPrefix: isGithubPages ? `/${repoName}/` : "",
  images: {
    unoptimized: true
  }
};

export default nextConfig;

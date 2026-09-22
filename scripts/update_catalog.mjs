import fs from "node:fs";
import path from "node:path";

const [, , tag, repository] = process.argv;

if (!/^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(tag ?? "")) {
  throw new Error(`Expected a semantic release tag such as v0.1.0, received: ${tag}`);
}
if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository ?? "")) {
  throw new Error(`Expected an OWNER/REPOSITORY value, received: ${repository}`);
}

const catalogPath = path.resolve(process.env.CATALOG_PATH ?? "catalog.json");
const catalog = JSON.parse(fs.readFileSync(catalogPath, "utf8"));
const extension = catalog.extensions?.find(
  (candidate) => candidate.name === "TimeStamp Extension",
);

if (!extension) {
  throw new Error("TimeStamp Extension entry is missing from catalog.json");
}

const version = tag.slice(1);
const releaseBase = `https://github.com/${repository}/releases/download/${tag}`;
const release = {
  name: tag,
  main_url: `${releaseBase}/TimeStamp-${version}.jar`,
  javadoc_urls: [`${releaseBase}/TimeStamp-${version}-javadoc.jar`],
  version_range: {
    min: "v0.6.0",
  },
};

extension.releases = [
  release,
  ...(extension.releases ?? []).filter((candidate) => candidate.name !== tag),
];

fs.writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`);
console.log(`Added ${tag} to ${catalogPath}`);

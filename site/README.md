# Doctor installation website

The existing Next.js site and GitHub Pages publishing workflow are preserved.
This update has not been published automatically.

## Content and downloads

- `app/page.tsx`: doctor-facing home page, native computer selector, first-recording
  tutorial, privacy notes, and keyboard-accessible troubleshooting disclosures.
- `data/install-guide.ts`: exact installer names, instructions, and current download.
- `public/downloads/TimeStamp-Doctor-0.1.0-SNAPSHOT.zip`: copy of the tested local
  doctor package built September 23, 2026. Old downloads are preserved but not linked
  by the new guide.
- `public/downloads/DOCTOR-INSTALL.txt`: downloadable written instructions.
- `app/globals.css`: desktop/mobile layout, keyboard focus, reduced motion, and print
  layout. Printing uses the currently selected computer's instructions.

The package has not been clinically validated. Do not remove the preview notice or
describe confidence as measured accuracy. QuPath download guidance links to the
official version 0.6.0 release. No patient data is collected by this guide.

## Verification

Run `npm run build` for the regular site. Run `GITHUB_PAGES=true npm run build`
followed by `npm run test:guide` to verify the static export, all three rendered
computer guides, anchor destinations, installer filenames, ZIP checksums, and
download/asset paths. Tests require `unzip` (available on the Pages Ubuntu runner).
These checks are not interactive browser or visual accessibility certification.

The Pages workflow runs the guide checks before publishing. A push to main that
includes this site triggers public deployment; review changes before pushing.
Do not publish unrelated workspace changes.

## Social preview asset

`public/og.png` was created with the built-in image-generation tool. Its text was
visually checked. No generated image is used as a screenshot of the actual app.

Prompt: landscape social link preview for the TimeStamp for QuPath doctor
installation website; one complete polished typographic card, approximately
1200x630; warm off-white #f7f8f5, deep forest teal #145c50, charcoal type; restrained
pale mint panel with three numbered steps. Large headline “Your observations.
One recorded session.” Brand “TimeStamp for QuPath”. Subtitle “Install. Record.
Review.” Panel: “01 Install TimeStamp”, “02 Make a test recording”, “03 Review and
save”. Pill: “Getting started guide”. Generous margins, crisp accessible sans-serif
type, understated editorial healthcare software design. No doctors, patient
imagery, medical claims, invented labels, website URL, or watermark.

Because GitHub Pages is a static export, metadata uses its configured public URL
rather than a request-time host. For another host, set `NEXT_PUBLIC_SITE_URL` to its
absolute site URL including any path prefix and trailing slash before building.

import type { Metadata } from "next";
import "./globals.css";

// GitHub Pages is a static export: use its configured public origin rather than
// a request-time host. Other deployments can supply their own public site URL.
const siteUrl = new URL(process.env.NEXT_PUBLIC_SITE_URL ??
  (process.env.GITHUB_PAGES === "true"
    ? "https://nighthunter57.github.io/QuPath_TimeStamp_extension/"
    : "http://127.0.0.1:3010/"));
const socialImage = new URL("og.png", siteUrl).href;

export const metadata: Metadata = {
  metadataBase: siteUrl,
  title: "TimeStamp for QuPath | Installation & First Recording",
  description:
    "Step-by-step TimeStamp installation for Windows, Mac, and Linux. Set up your microphone, make a test recording, then review and save your QuPath session.",
  openGraph: {
    title: "TimeStamp for QuPath — Your observations. One recorded session.",
    description:
      "A clear installation and first-recording guide for doctors. Install. Record. Review.",
    type: "website",
    images: [{ url: socialImage, width: 1730, height: 909, alt: "TimeStamp for QuPath: Install. Record. Review." }]
  },
  twitter: {
    card: "summary_large_image",
    title: "TimeStamp for QuPath — Getting started",
    description: "Installation and first-recording instructions for Windows, Mac, and Linux.",
    images: [socialImage]
  },
};

export default function RootLayout({
  children
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

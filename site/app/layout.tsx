import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TimeStamp Extension for QuPath",
  description:
    "A QuPath extension for synchronized viewer events, view bounds, annotation geometry, and optional live transcripts.",
  openGraph: {
    title: "TimeStamp Extension for QuPath",
    description:
      "Capture synchronized QuPath interactions, annotation geometry, and transcripts for whole-slide image review sessions.",
    type: "website"
  }
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

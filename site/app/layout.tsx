import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TimeStamp Extension | Support and Updates",
  description:
    "Report TimeStamp Extension problems, review release notes, and install updates for QuPath.",
  openGraph: {
    title: "TimeStamp Extension Support and Updates",
    description:
      "Support tickets, release notes, downloads, and update instructions for the TimeStamp QuPath extension.",
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

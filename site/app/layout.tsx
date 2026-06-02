import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Download TimeStamp Extension for QuPath",
  description:
    "Download QuPath and install the TimeStamp Extension with a doctor-friendly, no-code walkthrough.",
  openGraph: {
    title: "Download TimeStamp Extension for QuPath",
    description:
      "A no-code download and drag-and-drop install page for doctors using TimeStamp Extension in QuPath.",
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

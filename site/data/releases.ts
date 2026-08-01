export type ReleaseNotice = {
  version: string;
  date: string;
  status: "Preview" | "Stable";
  title: string;
  summary: string;
  improvements: string[];
  fixes: string[];
};

export const releaseNotices: ReleaseNotice[] = [
  {
    version: "0.1.0-SNAPSHOT",
    date: "July 31, 2026",
    status: "Preview",
    title: "More reliable live transcription and a quieter timestamp display",
    summary:
      "This development build focuses on transcript completeness, timestamp synchronization, and reducing visual distraction during slide review.",
    improvements: [
      "Live transcript output keeps stable phrases visible while the current phrase is still being revised.",
      "The final transcript pass uses the complete recording to recover words that may be missed during live processing.",
      "The recording overlay now uses one compact line with a small status indicator and HH:mm:ss time.",
      "Recent click and annotation messages clear after four seconds while complete timestamps remain in exported logs."
    ],
    fixes: [
      "Corrected QuPath extension-directory detection and duplicate JAR loading.",
      "Improved transcript Python executable discovery and preference handling.",
      "Added one-command development deployment and release catalog automation.",
      "Validated long-form pathology and radiology speech with timestamp-delay checks."
    ]
  }
];

export const latestRelease = releaseNotices[0];

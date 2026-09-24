export type Computer = "windows" | "mac" | "linux";

export const doctorPackage = {
  version: "0.1.0-SNAPSHOT",
  date: "September 23, 2026",
  href: "./downloads/TimeStamp-Doctor-0.1.0-SNAPSHOT.zip",
  instructions: "./downloads/DOCTOR-INSTALL.txt",
};

export const computers: Record<Computer, {
  name: string;
  detail: string;
  qupath: string;
  extract: string;
  installer: string;
  run: string;
  help: string;
}> = {
  windows: {
    name: "Windows",
    detail: "Windows 10 / 11",
    qupath: "Choose the Windows .msi installer on the QuPath 0.6.0 download page and follow its setup instructions.",
    extract: "In Downloads, right-click the TimeStamp ZIP and choose Extract All. Open the extracted folder, then the TimeStamp-Doctor folder inside it. Do not run the installer from inside the ZIP.",
    installer: "Install TimeStamp on Windows.bat",
    run: "Double-click the file below. Keep the setup window open while it downloads the recording tools and speech models.",
    help: "If Windows or your organization blocks the installer, stop and ask your IT team to approve it. Do not disable security software or change your organization’s security settings.",
  },
  mac: {
    name: "Mac",
    detail: "Apple Silicon / Intel",
    qupath: "Choose the Mac arm64 .pkg for Apple Silicon, or the Mac x64 .pkg for an Intel Mac. You can check your processor in Apple menu → About This Mac.",
    extract: "In Downloads, double-click the TimeStamp ZIP. Open the extracted TimeStamp-Doctor folder.",
    installer: "Install TimeStamp.command",
    run: "Double-click the file below. A Terminal window opens and downloads the recording tools and speech models. Keep it open until setup finishes.",
    help: "If macOS blocks the file, confirm it came from your approved source and ask your IT team how to approve it. Do not disable macOS security protections.",
  },
  linux: {
    name: "Linux",
    detail: "May need IT assistance",
    qupath: "Choose the Linux archive on the QuPath 0.6.0 download page. Extract it and follow QuPath’s Linux installation instructions for your computer.",
    extract: "Extract the TimeStamp ZIP, open the TimeStamp-Doctor folder, and open a terminal in that folder.",
    installer: "Install TimeStamp on Linux.sh",
    run: "Run the command below from the extracted folder. The installer may request an administrator password to install the microphone support library (PortAudio). Ask IT if you do not have permission.",
    help: "If the microphone library cannot be installed, ask your IT team to install PortAudio for your Linux distribution, then run the installer again.",
  },
};

export const recorderSteps = [
  { title: "Open the recorder", text: "In QuPath, choose Extensions → TimeStamp Extension → Open Clinical Session Recorder. Open a non-identifying test slide if you want to check image actions too." },
  { title: "Check your microphone", text: "Open Settings, choose your microphone, and use Test microphone. Allow microphone access when asked. You do not need to set up Python yourself." },
  { title: "Start a short test", text: "Choose Start Recording. Wait for Recording, then say a few test sentences. Check the signal indicator, transcript, and recorded actions. Do not use patient information for this test." },
  { title: "Pause, then resume", text: "Choose Pause for a break. Choose Resume to continue the same recording. Pause does not finish the session or create the final transcript." },
  { title: "Choose Finish & review", text: "Finish & review stops recording and prepares the final transcript. This can take time. Replay uncertain words and correct the text, including numbers and negations. A confidence highlight is not a guarantee of correctness." },
  { title: "Save Session", text: "Choose Save Session and select the destination folder and export options. Include audio if you want to replay words after reopening. The folder choice appears here, not before you start recording." },
];

export const savedContents = [
  { title: "Reviewed transcript", text: "Your corrected text, kept alongside the original machine transcript so changes can be checked." },
  { title: "Word and phrase timings", text: "A timed transcript plus spreadsheet (CSV) files giving the time of every phrase and word." },
  { title: "Image actions", text: "Zoom, view, click and annotation actions with timestamps, as CSV and JSON." },
  { title: "Audio, if you include it", text: "Needed to replay words or Record more after reopening. Leave it out only if you do not need either." },
  { title: "A verification manifest", text: "Checksums that let TimeStamp confirm the folder is complete when you reopen it with Open saved session." },
];

export const helpItems = [
  { question: "The installer is taking a long time. Is it stuck?", answer: "The first setup downloads a private Python runtime and about 3.6 GB of speech models. Time depends on your internet connection and computer; it is not a five-minute installation guarantee. Keep the setup window open. If the download is interrupted, run the same installer again; completed model files are reused. If it reports an error, save the error text without patient information and ask for help." },
  { question: "The computer blocks the installer.", answer: "Hospital-managed computers may need IT approval. Ask IT to review the download and installation steps. Do not turn off security protections or bypass your organization’s policy." },
  { question: "TimeStamp does not appear in QuPath.", answer: "Close QuPath completely and reopen it. Check that you are using QuPath 0.6 and completed its first-time setup before installing TimeStamp. If you chose a custom QuPath user folder, ask your administrator to install TimeStamp in that folder; the installer assumes the default folder." },
  { question: "The installer finished, but the microphone does not work.", answer: "Installation can finish even if its microphone test fails. Connect a microphone, check your computer’s microphone permissions, and select the correct input in TimeStamp Settings. Run Test microphone inside QuPath before starting a session." },
  { question: "Do I need internet every time?", answer: "The normal installer downloads the default English live model and final transcription model. With those models installed, recording and transcription run locally. Changing to an uncached model or another language may require another download." },
  { question: "Can I reopen a saved session or add more to it?", answer: "Yes. In the recorder, choose More → Open saved session… and select the folder created by Save Session. Word replay and Record more need audio included in that save. If you corrected text and then Record more, TimeStamp asks you to compare your earlier corrections with the new transcript before keeping it." },
  { question: "Is this ready for routine clinical use?", answer: "This is a preview for supervised evaluation, not a clinically validated transcription system. The macOS runtime installation and upgrade have been checked; full model-download, real-microphone, long-session, and Windows/Linux acceptance tests remain. Follow your organization’s approval process and review every transcript." },
  { question: "If I close QuPath without saving, is the recording deleted?", answer: "No. Answering No to “Save the transcript and timestamps before closing QuPath?” only stops TimeStamp offering to recover that recording; it does not erase its audio. Working recordings remain in the QuPath user folder under timestamp/recordings, even if you exclude audio when exporting. Automatic deletion and application-level encryption are not configured. Follow your organization’s retention policy." },
];

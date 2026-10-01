export type Computer = "windows" | "mac" | "linux";

export const doctorPackage = {
  version: "0.2.0-preview.20261001.3",
  date: "October 1, 2026",
  href: "./downloads/TimeStamp-Doctor-0.2.0-preview.20261001.3.zip",
  instructions: "./downloads/DOCTOR-INSTALL.txt",
  guide: "./downloads/TimeStamp-Install-Guide.pdf",
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
    detail: "Windows 10 / 11 · x64",
    qupath: "Use the Windows .msi installer.",
    extract: "Right-click the ZIP and choose Extract All, then open the TimeStamp-Doctor folder.",
    installer: "Install TimeStamp on Windows.bat",
    run: "Double-click this file. A setup window opens.",
    help: "Blocked, or a missing DLL? Ask IT (it may need the Microsoft Visual C++ x64 runtime).",
  },
  mac: {
    name: "Mac",
    detail: "Apple Silicon / Intel",
    qupath: "Use the arm64 .pkg for Apple Silicon or the x64 .pkg for Intel (Apple menu → About This Mac).",
    extract: "Double-click the ZIP, then open the TimeStamp-Doctor folder.",
    installer: "Install TimeStamp.command",
    run: "Double-click this file. A Terminal window opens.",
    help: "If macOS blocks it, Control-click the file and choose Open. Still blocked? Ask IT.",
  },
  linux: {
    name: "Linux",
    detail: "May need IT help",
    qupath: "Use the Linux archive and QuPath's Linux instructions.",
    extract: "Extract the ZIP and open a terminal in the TimeStamp-Doctor folder.",
    installer: "Install TimeStamp on Linux.sh",
    run: "Run this command. It may ask for an administrator password.",
    help: "If it cannot install microphone support (PortAudio), ask IT to install it, then run the command again.",
  },
};

export const recorderSteps = [
  { title: "Open the recorder", text: "In QuPath: Extensions → TimeStamp Extension → Open Clinical Session Recorder. The first time, open Settings and use Test microphone." },
  { title: "Record", text: "Choose Start Recording and speak while you work. Pause for a break; Resume continues the same recording." },
  { title: "Finish & review", text: "Finish & review creates the final transcript. Check highlighted words, numbers and negations against the audio." },
  { title: "Save Session", text: "Choose a folder and name without patient details. Tick Include audio recording if you may want to replay or add to it later." },
];

export const goodToKnow = [
  { title: "Stays on this computer", text: "Recording and transcription run locally. Audio is not sent anywhere." },
  { title: "Always review the text", text: "This is a preview for supervised evaluation. Check every transcript before you rely on it." },
  { title: "Recordings are kept", text: "Working audio stays in your QuPath folder under timestamp/recordings until IT removes it, even if you don't save." },
];

export const helpItems = [
  { question: "Setup is slow or stopped", answer: "The 3.6 GB speech model download takes the longest. Keep the window open. If it was interrupted, run the installer again; finished files are reused." },
  { question: "The computer blocks the installer", answer: "Ask IT to approve it. Don't turn off security software." },
  { question: "TimeStamp is not in the Extensions menu", answer: "Quit and reopen QuPath. Check Preferences → User directory, then run the installer again and enter that folder." },
  { question: "The microphone doesn't work", answer: "Check it is connected and allowed in your computer's privacy settings, choose it in TimeStamp Settings, then use Test microphone." },
  { question: "\"No microphone signal detected\"", answer: "TimeStamp stopped receiving sound. Check the microphone or headset connection; the warning clears when sound returns." },
  { question: "The final transcript is taking too long", answer: "Choose Cancel final pass · keep saved audio. Later, use More → Retry final transcription." },
  { question: "Reopen a saved session", answer: "More → Open saved session…, then choose the saved folder. Replay and Record more need audio included when saving." },
  { question: "Is closing without saving a delete?", answer: "No. It does not erase the recording; the audio stays in your QuPath folder." },
  { question: "Can I use it for routine clinical work?", answer: "Not yet. It is not a clinically validated transcription system. Use it under supervision and review every transcript." },
];

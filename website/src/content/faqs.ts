export const faqs = [
  {
    question: 'What is FM AI Assistent?',
    answer: 'FM AI Assistent is a local companion application for Football Manager 2026. It loads a snapshot of your running save from memory so you can browse its data and optionally ask an AI assistant questions with relevant context.'
  },
  {
    question: 'Is it an official Football Manager product?',
    answer: 'No. FM AI Assistent is an independent community project and is not affiliated with, endorsed by or sponsored by Sports Interactive or SEGA.'
  },
  {
    question: 'Does it change my save or make transfers for me?',
    answer: 'It does not write decisions back into the running FM26 process. It reads data into a local snapshot for browsing and analysis. It can create a separate FM26 shortlist file and keep recruitment evidence in its own local database.'
  },
  {
    question: 'Can I use the database without an AI provider?',
    answer: 'Yes. Player, staff, club and competition browsing and filtering work independently of the AI chat. An AI provider is only needed for assistant conversations.'
  },
  {
    question: 'Which operating systems are supported?',
    answer: 'The project currently documents Windows 11 and Linux support. macOS and mobile support are not advertised.'
  },
  {
    question: 'Do I need Java?',
    answer: 'Not when you use a packaged desktop application or native image. The JAR distribution requires Java 25 or newer.'
  },
  {
    question: 'Which AI options are supported?',
    answer: 'The embedded chat currently supports locally installed Codex, Antigravity and GitHub Copilot CLIs, plus OpenRouter using an API key. The selected service may have its own account, usage limits or costs.'
  },
  {
    question: 'What information leaves my computer?',
    answer: 'FM data and tactic files are processed locally by the app. When you use an AI provider, your messages, enabled context and tool results needed for the answer reach that provider. Review its privacy terms before connecting.'
  },
  {
    question: 'How do I refresh after the game changes?',
    answer: 'Select Load data again after switching saves, changing clubs or advancing to data you want refreshed. The app uses an atomic local snapshot rather than promising continuous synchronization.'
  },
  {
    question: 'Can it analyze an exported tactic?',
    answer: 'Yes. You can select an FM26 .fmf tactic from the detected tactics folder or upload one in Context. The app decodes it locally and can include that context in enabled AI conversations.'
  },
  {
    question: 'Where can I download updates or report a problem?',
    answer: 'Use the project’s official GitHub Releases page for downloads and its GitHub Issues page for reproducible problems.'
  }
] as const;

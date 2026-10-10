export const site = {
  name: 'FM AI Assistent',
  tagline: 'AI companion for Football Manager 2026',
  origin: 'https://fmaiassistant.com',
  repository: 'https://github.com/JelmerBouma1985/fm-ai-assistent',
  releases: 'https://github.com/JelmerBouma1985/fm-ai-assistent/releases',
  latestRelease: 'https://github.com/JelmerBouma1985/fm-ai-assistent/releases/latest',
  issues: 'https://github.com/JelmerBouma1985/fm-ai-assistent/issues',
  releaseNotes: 'https://github.com/JelmerBouma1985/fm-ai-assistent/blob/master/RELEASE_NOTES.md',
  description: 'Explore scouting, recruitment, squad analysis and AI-assisted insights using data loaded from your Football Manager 2026 save.'
} as const;

export const featureLinks = [
  { href: '/features/scouting/', label: 'Player scouting' },
  { href: '/features/tactics/', label: 'Tactics & squad' },
  { href: '/features/recruitment/', label: 'Recruitment' },
  { href: '/features/staff/', label: 'Staff analysis' },
  { href: '/features/ai-assistant/', label: 'AI assistant' }
] as const;

export const toAbsoluteUrl = (path: string) => new URL(path, site.origin).toString();

package com.github.fmaiassistent.update;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.updates")
public class UpdateProperties {
    private boolean enabled = true, developerMode, checkOnStartup = true;
    private String source = "github", localDirectory = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isDeveloperMode() { return developerMode; }
    public void setDeveloperMode(boolean value) { developerMode = value; }
    public boolean isCheckOnStartup() { return checkOnStartup; }
    public void setCheckOnStartup(boolean value) { checkOnStartup = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public String getLocalDirectory() { return localDirectory; }
    public void setLocalDirectory(String value) { localDirectory = value; }
}

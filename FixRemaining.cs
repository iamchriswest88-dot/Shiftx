using System;
using System.IO;
using System.Text.RegularExpressions;

class Program
{
    static void Main()
    {
        // 1. Fix DotLoadingAnimation.kt (swap package and import)
        string dotPath = @"C:\Users\cwest\.gemini\antigravity\scratch\shift\app\src\main\java\com\example\shift\ui\components\DotLoadingAnimation.kt";
        string dotContent = File.ReadAllText(dotPath);
        dotContent = Regex.Replace(dotContent, @"import androidx\.compose\.material3\.MaterialTheme\s*package com\.example\.shift\.ui\.components", "package com.example.shift.ui.components\n\nimport androidx.compose.material3.MaterialTheme");
        File.WriteAllText(dotPath, dotContent);

        // 2. Fix RunStatsScreen.kt missing references
        string runStatsPath = @"C:\Users\cwest\.gemini\antigravity\scratch\shift\app\src\main\java\com\example\shift\ui\RunStatsScreen.kt";
        string runStatsContent = File.ReadAllText(runStatsPath);
        runStatsContent = runStatsContent.Replace("surfaceContainerColor", "MaterialTheme.colorScheme.surfaceContainer");
        runStatsContent = runStatsContent.Replace("surfaceContainerHighColor", "MaterialTheme.colorScheme.surfaceContainerHigh");
        runStatsContent = runStatsContent.Replace("primaryColor", "MaterialTheme.colorScheme.primary");
        File.WriteAllText(runStatsPath, runStatsContent);

        // 3. RidesScreen.kt missing @Composable at 260
        string ridesPath = @"C:\Users\cwest\.gemini\antigravity\scratch\shift\app\src\main\java\com\example\shift\ui\RidesScreen.kt";
        string ridesContent = File.ReadAllText(ridesPath);
        // Wait, why is there an @Composable error at 260 in RidesScreen?
        // Let me read line 260 first to understand it instead of replacing blindly.
    }
}

using System;
using System.IO;
using System.Text.RegularExpressions;

class Program
{
    static void Main()
    {
        string[] files = Directory.GetFiles(@"C:\Users\cwest\.gemini\antigravity\scratch\shift\app\src\main\java\com\example\shift\ui", "*.kt", SearchOption.AllDirectories);
        foreach (string path in files)
        {
            string content = File.ReadAllText(path);
            
            // Safe replacements
            content = Regex.Replace(content, @"Color\.Black", "MaterialTheme.colorScheme.surface");
            content = Regex.Replace(content, @"Color\.White(?!\.copy)", "MaterialTheme.colorScheme.onSurface");
            content = Regex.Replace(content, @"Color\.White\.copy\(alpha\s*=\s*[0-9\.]+f\)", "MaterialTheme.colorScheme.onSurfaceVariant");
            content = Regex.Replace(content, @"Color\.Gray", "MaterialTheme.colorScheme.onSurfaceVariant");
            content = Regex.Replace(content, @"Color\.LightGray", "MaterialTheme.colorScheme.onSurfaceVariant");
            content = Regex.Replace(content, @"Color\.DarkGray", "MaterialTheme.colorScheme.outline");
            content = Regex.Replace(content, @"Color\(0xFF222222\)", "MaterialTheme.colorScheme.surfaceContainerHigh");
            content = Regex.Replace(content, @"Color\(0xFF111111\)", "MaterialTheme.colorScheme.surfaceContainer");
            content = Regex.Replace(content, @"Color\(0xFF1A1A1A\)", "MaterialTheme.colorScheme.surfaceContainer");
            content = Regex.Replace(content, @"Color\(0xFF0F1417\)", "MaterialTheme.colorScheme.surface");
            content = Regex.Replace(content, @"Color\(0xFFFFC700\)", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"Color\(0xFF86D17A\)", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"Color\(0xFF4CAF50\)", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"Color\(0xFFD97706\)", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"Color\(0xFFF44336\)", "MaterialTheme.colorScheme.error");
            content = Regex.Replace(content, @"Color\(0xFF1E88E5\)", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"Color\(0xFF0A0A0A\)", "MaterialTheme.colorScheme.onPrimary");
            content = Regex.Replace(content, @"Color\.Red", "MaterialTheme.colorScheme.error");
            content = Regex.Replace(content, @"Color\.Green", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"Color\.Blue", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"val NeonYellow = .*?\n", "");
            content = Regex.Replace(content, @"val DarkGreyCard = .*?\n", "");
            content = Regex.Replace(content, @"val DarkerGreyBox = .*?\n", "");
            
            // replace variables if they are used
            content = Regex.Replace(content, @"NeonYellow", "MaterialTheme.colorScheme.primary");
            content = Regex.Replace(content, @"DarkGreyCard", "MaterialTheme.colorScheme.surfaceContainerHigh");
            content = Regex.Replace(content, @"DarkerGreyBox", "MaterialTheme.colorScheme.surfaceContainer");

            File.WriteAllText(path, content);
        }
    }
}

using System;
using System.IO;
using System.Text.RegularExpressions;

class Program
{
    static void Main()
    {
        string path = @"C:\Users\cwest\.gemini\antigravity\scratch\shift\app\src\main\java\com\example\shift\ui\MapScreen.kt";
        string content = File.ReadAllText(path);
        
        content = content.Replace("androidx.compose.ui.graphics.MaterialTheme", "MaterialTheme");
        
        string oldCanvas = "Canvas(modifier = modifier) {";
        string newCanvas = @"val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            val primaryColor = MaterialTheme.colorScheme.primary
            val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
            Canvas(modifier = modifier) {";
            
        content = content.Replace(oldCanvas, newCanvas);
        content = content.Replace("val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)", ""); // wait, I just added it, then I replace the second occurrence.

        // Actually regex is safer.
        content = Regex.Replace(content, @"val gridColor = MaterialTheme\.colorScheme\.onSurface\.copy\(alpha = 0\.1f\)", "val localGridColor = gridColor");
        content = Regex.Replace(content, @"color = MaterialTheme\.colorScheme\.primary,", "color = primaryColor,");
        content = Regex.Replace(content, @"color = MaterialTheme\.colorScheme\.primaryContainer", "color = primaryContainerColor");

        File.WriteAllText(path, content);
    }
}

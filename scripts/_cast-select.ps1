Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CastKeys {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  public static string Title(IntPtr h) {
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512); return sb.ToString();
  }
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Key(byte vk, bool down) {
    keybd_event(vk, 0, down ? (uint)0 : (uint)2, UIntPtr.Zero);
  }
}
"@

$fg = [CastKeys]::GetForegroundWindow()
$fgTitle = [CastKeys]::Title($fg)
Write-Host "fg='$fgTitle'"
if ($fgTitle -notmatch "Quick settings") {
  Write-Host "ERROR: panel not open"
  exit 1
}

# Tab backwards from "More cast settings" to reach device row, screenshot each step
for ($step = 1; $step -le 8; $step++) {
  [CastKeys]::Key(0x09, $true)
  [CastKeys]::Key(0x09, $false)
  Start-Sleep -Milliseconds 150
  $shot = [System.Drawing.Bitmap]::new(864, 1692)
  $g = [System.Drawing.Graphics]::FromImage($shot)
  $g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
  $shot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/tab-$step.png")
  $shot.Dispose()
  Write-Host "tab $step saved"
}
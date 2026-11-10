# Invisible Minecraft windows for playtest.py: off screen, never focused, input via PostMessage.
# Run with Windows PowerShell 5.1 (powershell.exe): pwsh 7 cannot Add-Type System.Drawing.
# usage: mcwin.ps1 guard <seconds> | key <vk> | click <x> <y> | shot <png> | info | watch
# $env:MCPID picks the window of one java process; 0 = the first Minecraft window found.
# PrintWindow (shot) returns a stale frame until the window got some input: prefer F2 in game.
param([string]$cmd, [string]$a1, [string]$a2)
$mcpid = [int]($env:MCPID)

Add-Type -ReferencedAssemblies System.Drawing @"
using System; using System.Runtime.InteropServices; using System.Text; using System.Drawing; using System.Drawing.Imaging;
public static class W {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int i);
  [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr h, int i, int v);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public static IntPtr Find() { return Find(0); }
  public static System.Collections.Generic.List<IntPtr> All() { var l = new System.Collections.Generic.List<IntPtr>(); EnumWindows((h, x) => { if (!IsWindowVisible(h)) return true; var sb = new StringBuilder(256); GetWindowText(h, sb, 256); var t = sb.ToString(); if (t.StartsWith("Minecraft") && !t.Contains("Launcher")) { uint pid; GetWindowThreadProcessId(h, out pid); try { var p = System.Diagnostics.Process.GetProcessById((int)pid); if (p.ProcessName == "java" || p.ProcessName == "javaw") l.Add(h); } catch {} } return true; }, IntPtr.Zero); return l; }
  public static IntPtr Find(int want) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
      var t = sb.ToString();
      if (t.StartsWith("Minecraft") && !t.Contains("Launcher")) {
        uint pid; GetWindowThreadProcessId(h, out pid);
        try { var p = System.Diagnostics.Process.GetProcessById((int)pid); if ((p.ProcessName == "java" || p.ProcessName == "javaw") && (want == 0 || want == (int)pid)) { found = h; return false; } } catch {}
      }
      return true; }, IntPtr.Zero);
    return found;
  }
  public static void Hide(IntPtr h) {
    int ex = GetWindowLong(h, -20);
    SetWindowLong(h, -20, (ex | 0x08000000 | 0x00040000)); // NOACTIVATE | APPWINDOW
    SetWindowPos(h, IntPtr.Zero, -30000, -30000, 0, 0, 0x0001 | 0x0004 | 0x0010); // NOSIZE|NOZORDER|NOACTIVATE
  }
  public static void Shot(IntPtr h, string path) {
    RECT r; GetClientRect(h, out r);
    using (var bmp = new Bitmap(r.R, r.B)) { using (var g = Graphics.FromImage(bmp)) { var dc = g.GetHdc(); PrintWindow(h, dc, 3); g.ReleaseHdc(dc); } bmp.Save(path, ImageFormat.Png); }
  }
  public static void Click(IntPtr h, int x, int y) {
    var lp = (IntPtr)((y << 16) | (x & 0xFFFF));
    PostMessage(h, 0x0200, IntPtr.Zero, lp); System.Threading.Thread.Sleep(80);
    PostMessage(h, 0x0201, (IntPtr)1, lp); System.Threading.Thread.Sleep(80);
    PostMessage(h, 0x0202, IntPtr.Zero, lp);
  }
}
"@

switch ($cmd) {
  'watch' {
    # wait up to 6 min for the window, hide it immediately, give focus back
    $deadline = (Get-Date).AddMinutes(6)
    while ((Get-Date) -lt $deadline) {
      $fg = [W]::GetForegroundWindow()
      $h = [W]::Find()
      if ($h -ne [IntPtr]::Zero) {
        [W]::Hide($h)
        Start-Sleep -Milliseconds 300; [W]::Hide($h)
        if ($fg -ne $h -and $fg -ne [IntPtr]::Zero) { [void][W]::SetForegroundWindow($fg) }
        # keep enforcing for 90 s (MC may re-position itself while loading)
        $until = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $until) { $r = New-Object W+RECT; [void][W]::GetWindowRect($h, [ref]$r); if ($r.L -gt -20000) { [W]::Hide($h) }; Start-Sleep -Milliseconds 150 }
        "hidden $h"; return
      }
      Start-Sleep -Milliseconds 100
    }
    "no window"
  }
  'shot'  { $h = [W]::Find($mcpid); if ($h -eq [IntPtr]::Zero) { "no window"; return }; [W]::Shot($h, $a1); $r = New-Object W+RECT; [void][W]::GetClientRect($h, [ref]$r); "saved $a1 ($($r.R)x$($r.B))" }
  'click' { $h = [W]::Find($mcpid); [W]::Click($h, [int]$a1, [int]$a2); "clicked $a1,$a2" }
  'key'   { $h = [W]::Find($mcpid); [void][W]::PostMessage($h, 0x0100, [IntPtr][int]$a1, [IntPtr]1); Start-Sleep -Milliseconds 80; [void][W]::PostMessage($h, 0x0101, [IntPtr][int]$a1, [IntPtr]0xC0000001); "key $a1" }
  'guard' {
    # hide every Minecraft window that shows up during the next $a1 seconds, without taking focus
    $until = (Get-Date).AddSeconds([int]$a1)
    $lastFg = [IntPtr]::Zero
    while ((Get-Date) -lt $until) {
      $all = [W]::All(); $fg = [W]::GetForegroundWindow()
      if (-not ($all -contains $fg)) { $lastFg = $fg }
      foreach ($h in $all) { $r = New-Object W+RECT; [void][W]::GetWindowRect($h, [ref]$r); if ($r.L -gt -20000) { [W]::Hide($h); if ($lastFg -ne [IntPtr]::Zero) { [void][W]::SetForegroundWindow($lastFg) }; "hid $h" } }
      Start-Sleep -Milliseconds 100
    }
  }
  'info'  { $h = [W]::Find($mcpid); $r = New-Object W+RECT; [void][W]::GetWindowRect($h, [ref]$r); "hwnd $h at $($r.L),$($r.T) fg=$([W]::GetForegroundWindow())" }
}

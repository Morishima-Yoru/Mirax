import os

path = 'C:/Users/User/Documents/workspace/mirax/Mirax/scripts/pointer-probe/MultiTouchProbe.cs'

lines = []
lines.append('using System;')
lines.append('using System.Collections.Generic;')
lines.append('using System.IO;')
lines.append('using System.Runtime.InteropServices;')
lines.append('using System.Text;')
lines.append('using System.Threading;')
lines.append('')
lines.append('class MultiTouchProbe')
lines.append('{')
lines.append('    [DllImport("user32.dll")]')
lines.append('    static extern IntPtr CreateWindowEx(uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,')
lines.append('        int x, int y, int nWidth, int nHeight, IntPtr HWNDParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);')
lines.append('')
lines.append('    [DllImport("user32.dll")]')
lines.append('    static extern bool DestroyWindow(IntPtr"h
lines.append('')

with open(path, 'w') as f:
    f.write('\n'.join(lines))
print('ok')
import os

path = 'C:/Users/User/Documents/workspace/mirax/Mirax/scripts/pointer-probe/MultiTouchProbe.cs'

content = r"""using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

class MultiTouchProbe
{
    [DllImport("user32.dll")]
    static extern IntPtr CreateWindowEx(uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight, IntPtr HWNDParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll")]
    static extern bool DestroyWindow(IntPtr"h
"""

with open(path, 'w') as f:
    f.write(content)
print('ok')
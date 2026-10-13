param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class SessionOwners {
 [StructLayout(LayoutKind.Sequential)] public struct UniqueProcess {public uint pid; public System.Runtime.InteropServices.ComTypes.FILETIME started;}
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct ProcessInfo {
  public UniqueProcess process;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=256)] public string name;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=64)] public string service;
  public uint appType; public uint status; public uint session;
  [MarshalAs(UnmanagedType.Bool)] public bool restartable;
 }
 [DllImport("rstrtmgr.dll",CharSet=CharSet.Unicode)] static extern int RmStartSession(out uint handle,uint flags,string key);
 [DllImport("rstrtmgr.dll",CharSet=CharSet.Unicode)] static extern int RmRegisterResources(uint handle,uint files,string[] paths,uint apps,IntPtr processes,uint services,string[] names);
 [DllImport("rstrtmgr.dll")] static extern int RmGetList(uint handle,out uint needed,ref uint count,[In,Out] ProcessInfo[] info,ref uint reason);
 [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint handle);
 public static uint[] Read(string path) {
  uint h; if(RmStartSession(out h,0,Guid.NewGuid().ToString("N"))!=0)throw new Exception("ownership_unavailable");
  try {if(RmRegisterResources(h,1,new[]{path},0,IntPtr.Zero,0,null)!=0)throw new Exception("ownership_unavailable");
   uint needed=0,count=0,reason=0; int result=RmGetList(h,out needed,ref count,null,ref reason);
   if(result==0)return new uint[0]; if(result!=234||needed>128)throw new Exception("ownership_unavailable");
   count=needed;var rows=new ProcessInfo[count];if(RmGetList(h,out needed,ref count,rows,ref reason)!=0)throw new Exception("ownership_unavailable");
   var pids=new uint[count];for(int i=0;i<count;i++)pids[i]=rows[i].process.pid;return pids;
  }finally{RmEndSession(h);}
 }
}
'@
# Query registered resource users only. Never stop/restart a process or read/edit rollout contents.
ConvertTo-Json -Compress -InputObject @([SessionOwners]::Read([IO.Path]::GetFullPath($Path)))

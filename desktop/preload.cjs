const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('companion', Object.freeze({ action: (type, payload = {}) => ipcRenderer.invoke('companion-action', type, payload) }));

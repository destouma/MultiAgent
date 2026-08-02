import { create } from 'zustand';

type SplitViewState = {
  open: boolean;
  folderPath: string | null;
  pickerOpen: boolean;
  pickerFolderPath: string | null;
  openPicker: (folderPath: string) => void;
  closePicker: () => void;
  openSplit: (folderPath: string) => void;
  closeSplit: () => void;
};

export const useSplitViewStore = create<SplitViewState>((set) => ({
  open: false,
  folderPath: null,
  pickerOpen: false,
  pickerFolderPath: null,
  openPicker: (folderPath) => set({ pickerOpen: true, pickerFolderPath: folderPath }),
  closePicker: () => set({ pickerOpen: false, pickerFolderPath: null }),
  openSplit: (folderPath) => set({ open: true, folderPath }),
  closeSplit: () => set({ open: false }),
}));

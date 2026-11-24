# fat32-lib 0.6.5, patched

Source of [fat32-lib](https://github.com/waldheinz/fat32-lib) 0.6.5 (tag `fat32-lib-0.6.5`),
by Matthias Treydte and JNode.org, licensed under the GNU LGPL 2.1 or later
(see the header of each file). It is kept here instead of the Maven artifact so it can be patched.

Changes from the release:

- `fat/BootSector.read()` read the sectors-per-cluster byte as signed, so volumes with
  128 sectors per cluster (64 KiB clusters, common on FAT16 drives of 2–4 GB and some FAT32 drives)
  were refused with "suspicious sectors per cluster count -128". It is now read unsigned.

# Medtrum firmware analysis tools

Install pinned dependencies:

```powershell
python -m pip install -r tools/medtrum_re/requirements.txt
```

Raw images always require an explicit load address. This prevents an old report
or a filename from silently becoming an address assumption. Example:

```powershell
python tools/medtrum_re/dump_strings.py bin_file_jn.bin --base 0x26000
python tools/medtrum_re/build_callgraph.py bin_file_jn.bin --base 0x26000 --output callgraph.csv
python tools/medtrum_re/locate_dispatch_tables.py bin_file_jn.bin --base 0x26000
python tools/medtrum_re/extract_command_dispatch.py bin_file_jn.bin --base 0x26000 --dispatcher 0x2cc7a --end 0x2cfd2 --output base_command_map.csv
```

The tools are read-only. They do not contain BLE transport code and cannot send
commands to a pump.

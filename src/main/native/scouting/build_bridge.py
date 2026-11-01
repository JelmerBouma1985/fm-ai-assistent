"""Developer-only bridge build; the application has no TCC/CE dependency.

Usage: uv run --python 3.13 --with capstone python build_bridge.py --tcc-dir DIR
Uses the installed x64 TCC library, compiles without imports, and verifies the
relocation table against a second independent compilation/allocation. Generated
hex is a reviewed runtime resource, not a Maven build artifact.
"""
import argparse, ctypes as C, hashlib
from pathlib import Path
from capstone import Cs, CS_ARCH_X86, CS_MODE_64, CS_AC_WRITE
from capstone.x86_const import X86_OP_MEM

parser=argparse.ArgumentParser()
parser.add_argument('--tcc-dir',type=Path,required=True)
args=parser.parse_args()
with Path(__file__).with_name('scouting_bridge.c').open('rb') as stream:
    source=stream.read(65537)
assert len(source)<=65536
source=source.replace(b'\r\n',b'\n')
tcc=C.CDLL(str(args.tcc_dir/'tcc64-64.dll'))
def api(name,result,parameters):
    f=getattr(tcc,name);f.restype=result;f.argtypes=parameters;return f
new=api('tcc_new',C.c_void_p,[])
delete=api('tcc_delete',None,[C.c_void_p])
libpath=api('tcc_set_lib_path',None,[C.c_void_p,C.c_char_p])
include=api('tcc_add_sysinclude_path',C.c_int,[C.c_void_p,C.c_char_p])
options=api('tcc_set_options',None,[C.c_void_p,C.c_char_p])
output=api('tcc_set_output_type',C.c_int,[C.c_void_p,C.c_int])
compile_source=api('tcc_compile_string',C.c_int,[C.c_void_p,C.c_char_p])
relocate=api('tcc_relocate',C.c_int,[C.c_void_p,C.c_void_p])
symbol=api('tcc_get_symbol',C.c_void_p,[C.c_void_p,C.c_char_p])
k=C.WinDLL('kernel32',use_last_error=True)
k.VirtualAlloc.restype=C.c_void_p;k.VirtualAlloc.argtypes=[C.c_void_p,C.c_size_t,C.c_ulong,C.c_ulong]
k.VirtualFree.argtypes=[C.c_void_p,C.c_size_t,C.c_ulong]
allocations=[]
def build():
    state=new()
    try:
        libpath(state,str(args.tcc_dir/'tcclib').encode())
        assert include(state,str(args.tcc_dir/'include').encode())==0
        options(state,b'-nostdlib')
        assert output(state,1)==0 and compile_source(state,source)==0
        size=relocate(state,None);assert 0<size<65536
        address=k.VirtualAlloc(None,size,0x3000,0x40);assert address>0xffffffff
        allocations.append(address);assert relocate(state,address)==0
        symbols={name:symbol(state,name.encode())-address for name in
                 ['bridge_proc','bridge_install','bridge_uninstall','bridge_transaction','bridge_dispose','control_address']}
        raw=bytearray(C.string_at(address,size));relocations=[]
        offset=0
        while offset<=size-8:
            value=int.from_bytes(raw[offset:offset+8],'little')
            if address<=value<address+size:
                relocations.append(offset);raw[offset:offset+8]=(value-address).to_bytes(8,'little');offset+=8
            else:offset+=1
        md=Cs(CS_ARCH_X86,CS_MODE_64);md.detail=True
        for instruction in md.disasm(C.string_at(address,symbols['control_address']),address):
            for operand in instruction.operands:
                assert not (operand.type==X86_OP_MEM and operand.access&CS_AC_WRITE
                            and not operand.mem.base and not operand.mem.index),instruction
        return raw,relocations,symbols
    finally:delete(state)
try:
    first=build();second=build();assert first==second,'non-reproducible relocation image'
    raw,relocations,symbols=first
    destination=Path(__file__).parents[2]/'resources/native/scouting/scouting-bridge.properties'
    destination.parent.mkdir(parents=True,exist_ok=True)
    contents='# Rebuild: src/main/native/scouting/build_bridge.py\n'
    contents+='sourceSha256='+hashlib.sha256(source).hexdigest()+'\n'
    contents+='imageSha256='+hashlib.sha256(raw).hexdigest()+'\n'
    contents+='image='+raw.hex()+'\n'
    contents+='relocations='+','.join(map(str,relocations))+'\n'
    contents+='\n'.join(name+'='+str(offset) for name,offset in symbols.items())+'\n'
    destination.write_text(contents,encoding='ascii')
    print(f'PASS: {len(raw)} bytes, {len(relocations)} checked relocations; {destination}')
finally:
    for address in allocations:assert k.VirtualFree(address,0,0x8000)

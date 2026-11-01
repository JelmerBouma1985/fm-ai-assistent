/* Windows x64 one-shot FM UI-thread report transaction. Rebuild with build_bridge.py.
 * No external imports: every OS/native function and FM layout member is supplied
 * by the host after exact-build and identity validation. */
typedef unsigned long long U;
typedef U (*F0)(void);
typedef U (*F1)(U);
typedef U (*F2)(U,U);
typedef U (*F3)(U,U,U);
typedef U (*F4)(U,U,U,U);
typedef U (*F5)(U,U,U,U,U);
typedef U (*F7)(U,U,U,U,U,U,U);
typedef U (*F12)(U,U,U,U,U,U,U,U,U,U,U,U);
volatile U control_address;
#define S ((volatile U*)control_address)
#define AT(p,o) (*(volatile U*)((p)+(o)))
#define UID(p) (*(volatile unsigned int*)((p)+S[34]))
#define FN(i) (S[10]+S[i])

U bridge_dispose(U tag) {
 U extra=AT(tag,S[41]);
 if(extra){((F1)FN(48))(extra);((F1)FN(49))(extra);}
 ((F1)FN(50))(tag);return 0;
}
U bridge_transaction(void) {
 U contract,begin,end,cap,at,tag,extra,matching=0,context,entry,entryTag;
 unsigned short before;
 if (AT(S[10],S[32])!=S[11] || UID(S[11])!=S[12]
     || UID(S[15])!=S[16] || UID(S[18])!=S[19]
     || *(volatile unsigned int*)(S[10]+S[33])!=(unsigned int)S[22]
     || S[13]+S[51]!=S[11] || S[17]+S[38]!=S[18]) return 92;
 contract=AT(S[11],S[35]);
 if(!contract || AT(contract,S[36])!=S[20] || AT(S[20],S[37])!=S[21])return 93;
 contract=AT(S[18],S[35]);
 if(!contract || AT(contract,S[36])!=S[20])return 94;
 if(*(volatile int*)FN(79)>=-1)return 100;
 /* This is a POOL_MANAGER, not an allocation pool. Native callers select the
  * size-indexed descriptor before passing it to the freelist allocator. */
 begin=AT(FN(70),S[82]);end=AT(FN(70),S[83]);
 if(begin<0x10000 || begin>end || end>0x7FFFFFFFFFFFULL || end-begin>4000000
     || end-begin<(S[74]+1)*S[84])return 104;
 S[81]=AT(begin,S[74]*S[84]);
 if(S[81]<0x10000 || S[81]>0x7FFFFFFFFFFFULL || (S[81]&7))return 104;
 begin=AT(S[13],S[40]);end=AT(S[13],S[40]+8);cap=AT(S[13],S[40]+16);
 if(begin>end || end>cap || ((end-begin)&7) || end-begin>4000000)return 95;
 S[25]=(end-begin)/8;
 for(at=begin;at<end;at+=8){tag=AT(at,0);if(tag && AT(tag,S[57])==S[15])matching++;}
 S[27]=matching;
 before=*(volatile unsigned short*)(S[17]+S[39]);S[28]=before;S[29]=0;
 if(S[23])*(volatile unsigned short*)(S[17]+S[39])=0x6464;
 ((F7)FN(45))((U)&S[24],S[14],S[17],S[13],S[20],5,0xFFFF);
 *(volatile unsigned short*)(S[17]+S[39])=before;
 S[29]=*(volatile unsigned short*)(S[17]+S[39])==before;
 tag=S[24];
 if(!S[29] || !tag)return 96;
 extra=AT(tag,S[41]);
 if(AT(tag,S[57])!=S[15] || AT(tag,S[58])!=S[18] || AT(tag,S[59])!=S[20] || !extra){
  bridge_dispose(tag);S[24]=0;return 97;
 }
 S[30]=*(volatile unsigned char*)(extra+S[44]);
 if(S[30]>20 || (S[23] && S[30]!=20)){
  bridge_dispose(tag);S[24]=0;return 98;
 }
 *(volatile unsigned char*)(tag+S[42])=100;
 *(volatile unsigned char*)(tag+S[43])=5;
 /* Registration natively removes/disposes all prior reports for this PERSON. */
 ((F2)FN(46))(S[13],tag);
 S[26]=(AT(S[13],S[40]+8)-AT(S[13],S[40]))/8;
 if(((F4)FN(47))(S[13],S[14],0,0)!=tag || S[26]!=S[25]-matching+1)return 99;
 S[31]=matching?2:1;
 context=AT(S[13],S[61]);if(!context)return 100;context+=S[62];
 begin=AT(context,S[63]);end=AT(context,S[63]+8);cap=AT(context,S[63]+16);
 if(begin>end || end>cap || ((end-begin)&7) || end-begin>4000000)return 100;
 S[66]=(end-begin)/8;
 /* Use FM's own entry pool/initializer and force-centre insertion path.
  * The normal completion wrapper can discard the entry according to delivery
  * preferences, whereas the requested action explicitly targets this list. */
 entry=((F2)FN(60))(S[81],S[74]);if(!entry)return 101;
 S[80]=entry;
 for(at=0;at<S[74];at++)*(volatile unsigned char*)(entry+at)=0;
 AT(entry,0)=FN(65);
 *(volatile unsigned int*)(entry+S[75])=(unsigned int)S[22];
 *(volatile unsigned int*)(entry+S[76])=0xFFFFFFFF;
 AT(entry,S[77])=0x40000FFFFFFFFULL;
 *(volatile unsigned char*)(entry+S[78])=0;
 if(!(((F12)FN(71))(entry,S[14],S[13],0,tag,0,S[20],0,1,0,2,0xFFFFFFFF)&0xFF)){
  ((F2)FN(72))(entry,1);S[80]=0;return 103;
 }
 ((F2)FN(73))(context,(U)&S[80]);
 begin=AT(context,S[63]);end=AT(context,S[63]+8);cap=AT(context,S[63]+16);
 if(begin>end || end>cap || ((end-begin)&7) || end-begin>4000000)return 101;
 S[67]=(end-begin)/8;matching=0;
 for(at=begin;at<end;at+=8){
  entry=AT(at,0);if(!entry || AT(entry,0)!=FN(65))continue;
  entryTag=AT(entry,S[64]);
  if(entryTag && AT(entryTag,S[57])==S[15]){matching++;S[68]=entry;}
 }
 S[69]=matching;
 return matching==1?0:102;
}
U bridge_dispatch(U hwnd,unsigned int message,U wp,U lp){
 if(message==S[8] && wp==S[7] && lp==(U)S){
  U error;
  if(S[9])return 0;
  S[9]=1;
  ((F3)S[3])(hwnd,(U)-4,S[0]);
  S[54]=((F2)S[2])(hwnd,(U)-4)==S[0];
  if(!S[54]){S[9]=90;return 0;}
  if(((F0)S[5])()!=S[6]){S[9]=91;return 0;}
  error=bridge_transaction();S[9]=error?error:2;return error?0:1;
 }
 return ((F5)S[4])(S[0],hwnd,message,wp,lp);
}
U bridge_proc(U hwnd,unsigned int message,U wp,U lp){
 U result;S[56]++;result=bridge_dispatch(hwnd,message,wp,lp);S[56]--;return result;
}
U bridge_install(U control){
 U old;if(control!=(U)S)return 0;
 old=((F2)S[2])(S[1],(U)-4);
 if(!old || S[9])return 0;
 S[0]=old;S[55]=((F3)S[3])(S[1],(U)-4,(U)bridge_proc);
 return S[55]==old;
}
U bridge_uninstall(U control){
 U current;
 if(control!=(U)S)return 0;
 current=((F2)S[2])(S[1],(U)-4);
 if(current==(U)bridge_proc)((F3)S[3])(S[1],(U)-4,S[0]);
 return ((F2)S[2])(S[1],(U)-4)==S[0];
}

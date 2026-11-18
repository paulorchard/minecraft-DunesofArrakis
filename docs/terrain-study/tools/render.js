// usage: node render.js <worldRegionDir> <outPng> <x0> <z0> <size> [statsJson]
const {chunks}=require('./nbt.js'),fs=require('fs'),zlib=require('zlib'),path=require('path');
const [,, dir,out,X0,Z0,SIZE,statsOut]=process.argv;const x0=+X0,z0=+Z0,S=+SIZE;
const H=new Int16Array(S*S).fill(-32768);const B=new Array(S*S);const biomeNames=[];const bidx=new Map();
const bid=n=>{let i=bidx.get(n);if(i===undefined){i=biomeNames.length;biomeNames.push(n);bidx.set(n,i)}return i};
const B8=new Uint8Array(S*S);
function unpack(longs,bits,count){const per=Math.floor(64/bits),mask=(1n<<BigInt(bits))-1n;const r=new Array(count);for(let i=0;i<count;i++){const l=longs[Math.floor(i/per)];r[i]=Number((BigInt.asUintN(64,l)>>BigInt((i%per)*bits))&mask)}return r}
let nch=0,full=0;
for(const f of fs.readdirSync(dir).filter(f=>f.endsWith('.mca'))){
 for(const c of chunks(path.join(dir,f))){
  const cx=c.xPos,cz=c.zPos,bx=cx*16,bz=cz*16;
  if(bx+15<x0||bx>=x0+S||bz+15<z0||bz>=z0+S)continue;
  const hm=(c.Heightmaps&&(c.Heightmaps.WORLD_SURFACE||c.Heightmaps.WORLD_SURFACE_WG));if(!hm)continue;
  nch++;if(c.Status==='minecraft:full')full++;
  const minY=c.yPos*16;const hs=unpack(hm,9,256);
  const secByY=new Map();for(const s of c.sections)secByY.set(s.Y,s);
  for(let i=0;i<256;i++){const lx=i&15,lz=i>>4;const wx=bx+lx-x0,wz=bz+lz-z0;if(wx<0||wz<0||wx>=S||wz>=S)continue;
   const top=hs[i]+minY-1;H[wz*S+wx]=top;
   const s=secByY.get(Math.floor(top/16));let name='?';
   if(s&&s.biomes){const p=s.biomes.palette;if(p.length===1)name=p[0];else{const bits=Math.max(1,Math.ceil(Math.log2(p.length)));if(!s.biomes._u)s.biomes._u=unpack(s.biomes.data,bits,64);name=p[s.biomes._u[(((top&15)>>2)*16)+((lz>>2)*4)+(lx>>2)]]}}
   B8[wz*S+wx]=bid(name)}}}
// stats
const stats={chunks:nch,full,biomes:{}};
for(let i=0;i<S*S;i++){if(H[i]===-32768)continue;const n=biomeNames[B8[i]];(stats.biomes[n]??=[]).push(H[i])}
for(const n in stats.biomes){const a=stats.biomes[n].sort((x,y)=>x-y);const q=p=>a[Math.min(a.length-1,Math.floor(p*a.length))];stats.biomes[n]={cols:a.length,min:a[0],p5:q(.05),p50:q(.5),p95:q(.95),max:a[a.length-1]}}
// local relief (max-min in 32x32 windows) for desert-ish biomes
const relief={};
for(let wz=0;wz+32<=S;wz+=32)for(let wx=0;wx+32<=S;wx+=32){let mn=1e9,mx=-1e9,ok=true;const cnt={};for(let z=0;z<32;z++)for(let x=0;x<32;x++){const i=(wz+z)*S+wx+x;const h=H[i];if(h===-32768){ok=false;break}mn=Math.min(mn,h);mx=Math.max(mx,h);const n=biomeNames[B8[i]];cnt[n]=(cnt[n]||0)+1}if(!ok)continue;const dom=Object.entries(cnt).sort((a,b)=>b[1]-a[1])[0];if(dom[1]<900)continue;(relief[dom[0]]??=[]).push(mx-mn)}
for(const n in relief){const a=relief[n].sort((x,y)=>x-y);relief[n]={windows:a.length,median:a[Math.floor(a.length/2)],p90:a[Math.floor(a.length*.9)],max:a[a.length-1]}}
stats.relief32=relief;
if(statsOut)fs.writeFileSync(statsOut,JSON.stringify(stats,null,1));
// colours
const tint=n=>{n=n.replace('minecraft:','');if(/desert/.test(n))return[236,214,150];if(/badlands|mesa/.test(n))return[200,110,60];if(/ocean|river/.test(n))return[50,90,170];if(/beach/.test(n))return[230,225,170];if(/mountain|peak|cliff|stony|rocky|crag|windswept|slope|grove|jagged/.test(n))return[150,150,150];if(/savanna|steppe|dry|scrub/.test(n))return[190,175,90];return[90,150,70]};
const img=Buffer.alloc(S*S*3);const h=(x,z)=>{x=Math.max(0,Math.min(S-1,x));z=Math.max(0,Math.min(S-1,z));const v=H[z*S+x];return v===-32768?63:v};
for(let z=0;z<S;z++)for(let x=0;x<S;x++){const i=z*S+x;let r,g,b;if(H[i]===-32768){r=g=b=20}else{const t=tint(biomeNames[B8[i]]);const dx=h(x-1,z)-h(x+1,z),dz=h(x,z-1)-h(x,z+1);let sh=0.62+0.14*(dx+dz);sh=Math.max(.25,Math.min(1.35,sh));const el=Math.max(0,Math.min(1,(H[i]-60)/200));const k=sh*(0.8+0.35*el);r=Math.min(255,t[0]*k);g=Math.min(255,t[1]*k);b=Math.min(255,t[2]*k)}img[i*3]=r;img[i*3+1]=g;img[i*3+2]=b}
const SC=+(process.env.SCALE||1);let img2=img,SS=S;if(SC>1){SS=S*SC;img2=Buffer.alloc(SS*SS*3);for(let z=0;z<SS;z++)for(let x=0;x<SS;x++){const s=((z/SC|0)*S+(x/SC|0))*3,d=(z*SS+x)*3;img2[d]=img[s];img2[d+1]=img[s+1];img2[d+2]=img[s+2]}}
// PNG encode
const crcT=new Uint32Array(256);for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;crcT[n]=c>>>0}
const crc=b=>{let c=0xffffffff;for(const v of b)c=crcT[(c^v)&255]^(c>>>8);return(c^0xffffffff)>>>0};
const chunk=(t,d)=>{const l=Buffer.alloc(4);l.writeUInt32BE(d.length);const td=Buffer.concat([Buffer.from(t),d]);const c=Buffer.alloc(4);c.writeUInt32BE(crc(td));return Buffer.concat([l,td,c])};
const ih=Buffer.alloc(13);ih.writeUInt32BE(SS,0);ih.writeUInt32BE(SS,4);ih[8]=8;ih[9]=2;
const raw=Buffer.alloc((SS*3+1)*SS);for(let z=0;z<SS;z++){raw[z*(SS*3+1)]=0;img2.copy(raw,z*(SS*3+1)+1,z*SS*3,(z+1)*SS*3)}
fs.writeFileSync(out,Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ih),chunk('IDAT',zlib.deflateSync(raw,{level:6})),chunk('IEND',Buffer.alloc(0))]));
console.log('chunks',nch,'full',full,'wrote',out);

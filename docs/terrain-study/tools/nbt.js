const zlib=require('zlib'),fs=require('fs');
function parse(buf){let p=0;
 const rd={1:()=>buf.readInt8(p++),2:()=>{const v=buf.readInt16BE(p);p+=2;return v},3:()=>{const v=buf.readInt32BE(p);p+=4;return v},4:()=>{const v=buf.readBigInt64BE(p);p+=8;return v},5:()=>{const v=buf.readFloatBE(p);p+=4;return v},6:()=>{const v=buf.readDoubleBE(p);p+=8;return v},
 7:()=>{const n=buf.readInt32BE(p);p+=4;const a=buf.subarray(p,p+n);p+=n;return a},
 8:()=>{const n=buf.readUInt16BE(p);p+=2;const s=buf.toString('utf8',p,p+n);p+=n;return s},
 9:()=>{const t=buf[p++];const n=buf.readInt32BE(p);p+=4;const a=[];for(let i=0;i<n;i++)a.push(rd[t]());return a},
 10:()=>{const o={};for(;;){const t=buf[p++];if(t===0)break;const n=rd[8]();o[n]=rd[t]()}return o},
 11:()=>{const n=buf.readInt32BE(p);p+=4;const a=new Int32Array(n);for(let i=0;i<n;i++){a[i]=buf.readInt32BE(p);p+=4}return a},
 12:()=>{const n=buf.readInt32BE(p);p+=4;const a=new Array(n);for(let i=0;i<n;i++){a[i]=buf.readBigInt64BE(p);p+=8}return a}};
 const t=buf[p++];rd[8]();return rd[t]();}
function* chunks(file){const b=fs.readFileSync(file);for(let i=0;i<1024;i++){const o=b.readUInt32BE(i*4);const off=(o>>8)*4096;if(!off)continue;const len=b.readUInt32BE(off);const ct=b[off+4];let d=b.subarray(off+5,off+4+len);
 if(ct===2)d=zlib.inflateSync(d);else if(ct===1)d=zlib.gunzipSync(d);else if(ct===3){}else throw new Error('compression '+ct);yield parse(d)}}
module.exports={parse,chunks};

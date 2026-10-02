import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
const require=createRequire(import.meta.url);
const sharp=require('sharp'), opentype=require('opentype.js');
const root=fileURLToPath(new URL('../../',import.meta.url));
const raw=`${root}/assets/readme/screens`;
const font=opentype.loadSync(`${root}/tools/store-screenshots/fonts/GoogleSans-Medium.ttf`);
const names=['01-library','02-viewer','03-shared-albums','05-editor','06-video','03-collections','04-backup','05-share', '07-profile','08-phone-settings','09-about'];
if(process.argv[2]) {
 for(const name of names) {
  const input=resolve(process.argv[2],`${name}.png`);
  const metadata=await sharp(input).metadata();
  if(metadata.width!==1080 || metadata.height!==2400)throw new Error(`Capture ${name} at 1080 × 2400`);
  await sharp(input).resize({width:420}).webp({quality:90,effort:6}).toFile(`${raw}/${name}.webp`);
 }
 console.log(`Refreshed ${names.length} README screens`);
}
const screens=await Promise.all(names.map(async n=>(await sharp(`${raw}/${n}.webp`).resize(420,934).png().toBuffer()).toString('base64')));
const logo=(await sharp(await readFile(`${root}/assets/brand/alba-mark.svg`)).trim().resize({width:300}).png().toBuffer()).toString('base64');
function text(value,size,x,y,fill){const p=font.getPath(value,0,y,size);const b=p.getBoundingBox();p.fill=fill;return `<g transform="translate(${x-(b.x1+b.x2)/2},0)">${p.toSVG(3)}</g>`;}
const placements=[];
const angle=-25*Math.PI/180, width=197, height=423;
const c=Math.cos(angle),sn=Math.sin(angle);
// Screens stack vertically inside each column. Neighboring columns stagger vertically by 110px.
function intersects(cx,cy,rx,ry,rw,rh){
 const dx=rx-cx,dy=ry-cy;
 const axes=[[1,0],[0,1],[c,sn],[-sn,c]];
 return axes.every(([ax,ay])=>Math.abs(dx*ax+dy*ay)<rw/2*Math.abs(ax)+rh/2*Math.abs(ay)+width/2*Math.abs(c*ax+sn*ay)+height/2*Math.abs(-sn*ax+c*ay));
}
function addScreen(u,v,col,row){
 const x=800+u*c-v*sn, y=320+u*sn+v*c;
 if(!intersects(x,y,800,320,1600,640))return;
 if(intersects(x,y,800,320,370,94))throw new Error('Screen inside logo clearance');
 placements.push([x,y,((col*3+row*7)%screens.length+screens.length)%screens.length]);
}
for(let col=-10;col<=10;col++){
 const u=(col+0.5)*(width+42);
 if(col===-1||col===0){
  // Keep each center column on its existing axis. Stack outward above/below the logo.
  for(const sign of [-1,1]){
   let distance=0;
   while(intersects(800+u*c-sign*distance*sn,320+u*sn+sign*distance*c,800,320,370,94))distance++;
   distance+=48;
   for(let row=0;row<5;row++)addScreen(u,sign*(distance+row*(height+18)),col,sign*(row+1));
  }
 }else{
  for(let row=-4;row<=4;row++){
   const v=row*(height+18)+(Math.abs(col)%2)*110+150;
   const x=800+u*c-v*sn,y=320+u*sn+v*c;
   if(!intersects(x,y,800,320,370,94))addScreen(u,v,col,row);
  }
 }
}
for(let i=0;i<placements.length;i++)for(let j=i+1;j<placements.length;j++){
 const dx=placements[j][0]-placements[i][0],dy=placements[j][1]-placements[i][1];
 if(Math.abs(dx*c+dy*sn)<width-.01 && Math.abs(-dx*sn+dy*c)<height-.01)throw new Error('Screens overlap');
}
placements.sort((a,b)=>Math.hypot(a[0]-800,a[1]-320)-Math.hypot(b[0]-800,b[1]-320));
const priority=[8,2,0,9,1,3,4,5,10,7,6];
placements.forEach((p,i)=>p[2]=priority[i%priority.length]);
console.log('Grid cards:',placements.length);
const cards=placements.map(([x,y,index])=>`<g transform="translate(${x} ${y}) rotate(-25)"><rect x="-98.5" y="-211.5" width="197" height="423" rx="20" fill="#fff" filter="url(#shadow)"/><image x="-92.5" y="-205.5" width="185" height="411" href="data:image/png;base64,${screens[index]}" clip-path="url(#screen)"/></g>`).join('');
const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="3200" height="1280" viewBox="0 0 1600 640"><defs>
<linearGradient id="bg" x2="0.8" y2="1"><stop stop-color="#f5f3fc"/><stop offset=".55" stop-color="#f8eff4"/><stop offset="1" stop-color="#ffe9d2"/></linearGradient>
<filter id="shadow" x="-50%" y="-30%" width="200%" height="180%"><feDropShadow dx="0" dy="12" stdDeviation="12" flood-color="#40305d" flood-opacity=".16"/></filter>
<clipPath id="screen"><rect x="-92.5" y="-205.5" width="185" height="411" rx="13"/></clipPath></defs>
<rect width="1600" height="640" fill="url(#bg)"/>${cards}

<image x="622" y="281" width="130" height="78" href="data:image/png;base64,${logo}"/>
${text('Alba',100,876,355,'#242128')}

</svg>`;
await sharp(Buffer.from(svg)).webp({quality:92,effort:6,smartSubsample:true}).toFile(`${root}/assets/readme/banner.webp`);
console.log('Rendered assets/readme/banner.webp (3200 × 1280)');

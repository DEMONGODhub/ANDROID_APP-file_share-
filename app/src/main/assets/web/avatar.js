"use strict";
/* Profile pictures: six cute cats and dogs, or your own photo cropped into a shape. Needs $, J, dialog, toast, store, DEV from the page. */
const AV={ids:["d1","d2","d3","d4","d5","d6"],MAX_SRC:8*1024*1024,MAX_OUT:15800,mine:"d1",img:"",cache:new Map()};
(function(){
const eye=(x,y,c)=>`<ellipse cx="${x}" cy="${y}" rx="5.6" ry="6.4" fill="#fff"/><circle cx="${x}" cy="${y+.6}" r="4.1" fill="${c||"#2a1c10"}"/><circle cx="${x+1.5}" cy="${y-1.4}" r="1.5" fill="#fff"/>`;
const cat=(bg,fur,ear,belly,eyec,stripe)=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><circle cx="48" cy="48" r="48" fill="${bg}"/>
<path d="M18 46 14 14l30 16z" fill="${fur}"/><path d="m78 46 4-32-30 16z" fill="${fur}"/><path d="m22 38-1-14 15 8z" fill="${ear}"/><path d="m74 38 1-14-15 8z" fill="${ear}"/>
<ellipse cx="48" cy="56" rx="31" ry="27" fill="${fur}"/>${stripe?`<path d="M48 30v10M40 32l2 8M56 32l-2 8" stroke="${stripe}" stroke-width="3.4" stroke-linecap="round"/>`:""}
<ellipse cx="48" cy="66" rx="15" ry="11" fill="${belly}"/>${eye(35,52,eyec)}${eye(61,52,eyec)}
<path d="m44.5 61 3.5 4 3.5-4z" fill="#ff8aa6"/><path d="M48 65v3m-6 .5c2.4 3 5.2 3 6 0m0 0c.8 3 3.6 3 6 0" fill="none" stroke="#6b3b2a" stroke-width="1.7" stroke-linecap="round"/>
<circle cx="27" cy="63" r="4.5" fill="#ff9db3" opacity=".55"/><circle cx="69" cy="63" r="4.5" fill="#ff9db3" opacity=".55"/>
<path d="M9 58l13 3M9 67l13-2M87 58 74 61M87 67 74 65" stroke="#fff" stroke-opacity=".8" stroke-width="1.4" stroke-linecap="round"/></svg>`;
const dog=(bg,fur,ear,snout,patch)=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><circle cx="48" cy="48" r="48" fill="${bg}"/>
<ellipse cx="19" cy="50" rx="12" ry="25" fill="${ear}" transform="rotate(14 19 50)"/><ellipse cx="77" cy="50" rx="12" ry="25" fill="${ear}" transform="rotate(-14 77 50)"/>
<ellipse cx="48" cy="52" rx="29" ry="29" fill="${fur}"/>${patch?`<ellipse cx="35" cy="47" rx="11" ry="12" fill="${patch}"/>`:""}
<ellipse cx="48" cy="65" rx="17" ry="13" fill="${snout}"/>${eye(35,47)}${eye(61,47)}
<ellipse cx="48" cy="58" rx="7.4" ry="5.4" fill="#2a1c10"/><ellipse cx="46" cy="56.6" rx="2.2" ry="1.3" fill="#fff" opacity=".6"/>
<path d="M48 63v4m-7 1c2.6 3.4 5.4 3.4 7 0m0 0c1.6 3.4 4.4 3.4 7 0" fill="none" stroke="#2a1c10" stroke-width="1.8" stroke-linecap="round"/><path d="M44 69c0 7 8 7 8 0z" fill="#ff7b9c"/>
<circle cx="26" cy="62" r="4.5" fill="#ff9db3" opacity=".45"/><circle cx="70" cy="62" r="4.5" fill="#ff9db3" opacity=".45"/></svg>`;
const SVG={
 d1:cat("#FFD27A","#F29B3C","#FFB3A7","#FFE3BC","#2a1c10","#C46A14"),
 d2:cat("#9CC9F5","#9AA3AF","#F7B7C4","#E6EAF0","#2a1c10","#6B7280"),
 d3:cat("#C9A8F0","#2F2A38","#D98BA5","#4A4358","#F2C530",""),
 d4:dog("#A8E0A0","#B9784A","#7A4A2A","#F2D2B0","#7A4A2A"),
 d5:dog("#FFD1A6","#E8B45A","#C98A2E","#FFF0D2",""),
 d6:dog("#FFC2D9","#FFFFFF","#3A3A44","#F1EDE8","#3A3A44")};

/* the seven deadly sins: little devils for the bot, drawn as SVG */
const devil=(bg,skin,horn,face)=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><circle cx="48" cy="48" r="48" fill="${bg}"/>
<path d="M22 44 12 12l27 18z" fill="${horn}"/><path d="m74 44 10-32-27 18z" fill="${horn}"/><ellipse cx="48" cy="56" rx="30" ry="28" fill="${skin}"/>${face}</svg>`;
const bw=(a,b)=>`<path d="${a}" stroke="#2a1c10" stroke-width="3.6" stroke-linecap="round" fill="none"/><path d="${b}" stroke="#2a1c10" stroke-width="3.6" stroke-linecap="round" fill="none"/>`;
const SIN={
 s1:devil("#5B3FA0","#C9A5F2","#F2C530",`<path d="M28 24l6 9 8-12 6 12 8-12 6 9 6-9v14H28z" fill="#F2C530" stroke="#B8860B" stroke-width="1.5"/><path d="M30 55q5-5 10 0M56 55q5-5 10 0" stroke="#2a1c10" stroke-width="3.4" stroke-linecap="round" fill="none"/><path d="M38 72q10 7 20 0" stroke="#2a1c10" stroke-width="3.2" stroke-linecap="round" fill="none"/><circle cx="48" cy="64" r="2.6" fill="#7a4a8a"/>`),
 s2:devil("#2E7D4F","#B7E4A0","#D4A017",`<circle cx="35" cy="54" r="9" fill="#F2C530" stroke="#B8860B" stroke-width="2"/><circle cx="61" cy="54" r="9" fill="#F2C530" stroke="#B8860B" stroke-width="2"/><path d="M35 49v10M32 52c0-3 6-3 6 0s-6 2-6 5 6 3 6 0M61 49v10M58 52c0-3 6-3 6 0s-6 2-6 5 6 3 6 0" stroke="#7a5a00" stroke-width="1.6" fill="none"/><path d="M33 70q15 14 30 0z" fill="#2a1c10"/><path d="M38 70v4M45 70v5M52 70v5M58 70v4" stroke="#F2C530" stroke-width="2.4"/>`),
 s3:devil("#B83266","#FFB3C6","#E0245E",`<path d="M35 61c-9-6-8-14-1-14 3 0 5 2 5 4 0-2 2-4 5-4 7 0 8 8-1 14 0 0-4 3-4 4 0-1-4-4-4-4z" transform="translate(-4 -2) scale(.9)" fill="#E0245E"/><path d="M61 61c-9-6-8-14-1-14 3 0 5 2 5 4 0-2 2-4 5-4 7 0 8 8-1 14 0 0-4 3-4 4 0-1-4-4-4-4z" transform="translate(-4 -2) scale(.9)" fill="#E0245E"/><path d="M40 73q8-6 16 0q-8 8-16 0z" fill="#E0245E"/><path d="M40 73h16" stroke="#8a1038" stroke-width="1.4"/>`),
 s4:devil("#1F6F5C","#8FE0B8","#0E8A4E",`<ellipse cx="35" cy="54" rx="7" ry="5" fill="#fff"/><ellipse cx="61" cy="54" rx="7" ry="5" fill="#fff"/><circle cx="31" cy="54" r="3.3" fill="#7CFF3A"/><circle cx="57" cy="54" r="3.3" fill="#7CFF3A"/><circle cx="31" cy="54" r="1.4" fill="#000"/><circle cx="57" cy="54" r="1.4" fill="#000"/>${bw("M27 46l16 4","M69 46 53 50")}<path d="M38 74q10-7 20 0" stroke="#2a1c10" stroke-width="3.2" stroke-linecap="round" fill="none"/>`),
 s5:devil("#B8651F","#FFC48A","#8A3B0A",`<circle cx="33" cy="52" r="5.6" fill="#2a1c10"/><circle cx="63" cy="52" r="5.6" fill="#2a1c10"/><circle cx="34.6" cy="50.4" r="1.7" fill="#fff"/><circle cx="64.6" cy="50.4" r="1.7" fill="#fff"/><ellipse cx="48" cy="70" rx="14" ry="11" fill="#5a1410"/><ellipse cx="48" cy="76" rx="8" ry="5" fill="#FF7B9C"/><path d="M34 62h28" stroke="#fff" stroke-width="5" stroke-linecap="round"/><path d="M62 78q4 8 0 12" stroke="#7FD4FF" stroke-width="3.4" stroke-linecap="round" fill="none"/>`),
 s6:devil("#9E1B1B","#FF8A6B","#5a0a0a",`${bw("M24 44l20 8","M72 44 52 52")}<circle cx="35" cy="57" r="5" fill="#fff"/><circle cx="61" cy="57" r="5" fill="#fff"/><circle cx="36" cy="58" r="2.6" fill="#a00000"/><circle cx="60" cy="58" r="2.6" fill="#a00000"/><path d="M33 74q15-10 30 0l-4 6h-22z" fill="#2a1c10"/><path d="M38 75v5l3-3zM58 75v5l-3-3z" fill="#fff"/><path d="M30 14q-4 8 2 14M66 14q4 8-2 14" stroke="#FFB020" stroke-width="3" stroke-linecap="round" fill="none"/>`),
 s7:devil("#4A6FA5","#C6D4E8","#31507F",`<path d="M26 54q9 7 18 0M52 54q9 7 18 0" stroke="#2a1c10" stroke-width="3.6" stroke-linecap="round" fill="none"/><path d="M26 54h18M52 54h18" stroke="#2a1c10" stroke-width="2" opacity=".3"/><ellipse cx="48" cy="72" rx="6" ry="4.4" fill="#5a3a40"/><path d="M70 22h9l-9 10h9M80 10h7l-7 8h7" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>`)
};
Object.assign(SVG,SIN);
/* blessing heroes: original pixel-art characters shown while the host's power is active */
const PX={H:0,S:"#f2c9a0",E:"#2a1c10",B:0,D:"#2a1c10",G:"#f2c530"};
const hero=(bg,hat,body,over)=>{const R=[".....DDDDDD.....","....DHHHHHHD....","...DHHHHHHHHD...","...DHSSSSSSHD...","...DSSESSESSD...","....DSSSSSSD....","....DDBBBBDD....","...DBBBBBBBBD...","..DSDBBBBBBDSD..","...DBBBBBBBBD...","....DBBDDBBD....","....DDD..DDD...."];
 let r="";R.forEach((row,y)=>{for(let x=0;x<16;x++){const c=row[x];if(c==="."||c===undefined)continue;const f=c==="H"?hat:c==="B"?body:PX[c];r+=`<rect x="${x}" y="${y+2}" width="1.02" height="1.02" fill="${f}"/>`}});
 return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" shape-rendering="crispEdges"><circle cx="8" cy="8" r="8" fill="${bg}"/>${r}${over}</svg>`};
const rc=(x,y,w,h,f)=>`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${f}"/>`;
Object.assign(SVG,{
 b1:hero("#1f5f4a","#2e9b5f","#3b7f4a",rc(0,5,3,1,"#bff")+rc(1,8,2,1,"#bff")+rc(0,11,3,1,"#bff")+rc(5,14,2,1,"#cfe")+rc(9,14,2,1,"#cfe")),
 b2:hero("#7a3a1e","#c0392b","#8a5a2b",rc(13,3,1,1,"#e8d0a0")+rc(14,4,1,6,"#e8d0a0")+rc(13,10,1,1,"#e8d0a0")+rc(13,5,1,4,"#ddd")+rc(0,8,3,1,"#f2c530")+rc(1,7,1,3,"#f2c530")),
 b3:hero("#5a2a1a","#a33","#c0392b",rc(13,6,1,8,"#8a5a2b")+rc(12,5,3,3,"#bcc")+rc(15,5,1,3,"#8899a0")),
 b4:hero("#26486e","#aab4be","#7d8a96",rc(0,7,4,6,"#4a78b0")+rc(1,8,2,4,"#f2c530")+rc(0,7,4,1,"#2a1c10")+rc(0,12,4,1,"#2a1c10")),
 b5:hero("#3a1f6b","#6b3fb5","#5a3a9a",rc(12,9,3,3,"#6ee")+rc(13,10,1,1,"#fff")+rc(12,12,3,1,"#f2c530")+rc(1,2,1,1,"#f2c530")+rc(14,1,1,1,"#f2c530")+rc(0,9,1,1,"#fff")+rc(3,0,1,1,"#fff"))
});

/** a little 2D pumpkin, v = 0..3 for four varieties (food in the arena) */
AV.pumpkin=v=>{const c=[["#FF8A1F","#E0620A","#B8470A"],["#FFB02E","#E8880C","#B8650A"],["#F4D9A0","#D9B26A","#9A7A3A"],["#7DBF4E","#559A2E","#3A7220"]][v%4];
 return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><ellipse cx="32" cy="38" rx="27" ry="22" fill="${c[1]}"/><ellipse cx="20" cy="38" rx="14" ry="21" fill="${c[0]}" stroke="${c[2]}" stroke-width="1.6"/><ellipse cx="44" cy="38" rx="14" ry="21" fill="${c[0]}" stroke="${c[2]}" stroke-width="1.6"/><ellipse cx="32" cy="38" rx="11" ry="22" fill="${c[0]}" stroke="${c[2]}" stroke-width="1.6"/><path d="M30 17c0-6 2-9 4-11l3 2c-2 2-2 5-1 9z" fill="#4C8A2E" stroke="#2f5a1a" stroke-width="1.2"/><path d="M36 17c5-1 9 1 11 4-4 1-8 0-11-4z" fill="#6DB33F"/>
 <path d="M22 34l5 4-6 1zM42 34l-5 4 6 1zM26 46q6 5 12 0l-2 3-3-2-3 2z" fill="#3a1a05" opacity=".75"/></svg>`};
AV.svg=k=>SVG[k];
/** URL of a picture: a built-in cat or dog, or the uploaded one of connected player `id` */
window.avSrc=(key,id)=>SVG[key]?"data:image/svg+xml;charset=utf-8,"+encodeURIComponent(SVG[key]):(key&&id?"/api/avatar?id="+encodeURIComponent(id)+"&v="+encodeURIComponent(key):"");
/** Loaded Image for canvas drawing, or null until it arrives */
window.avImage=(key,id)=>{
  const u=window.avSrc(key,id);if(!u)return null;const ck=SVG[key]?key:id+"|"+key;let e=AV.cache.get(ck);
  if(!e){e={img:new Image(),ok:false};e.img.onload=()=>{e.ok=true};e.img.onerror=()=>{e.bad=true};e.img.src=u;AV.cache.set(ck,e);if(AV.cache.size>80)AV.cache.delete(AV.cache.keys().next().value)}
  return e.ok?e.img:null;
};

const rd=k=>{try{return localStorage.getItem(k)}catch(e){return null}};
const wr=(k,v)=>{try{localStorage.setItem(k,v)}catch(e){}};
(function load(){
  const k=rd("ls_av");AV.img=rd("ls_avimg")||"";
  if(k==="c"&&AV.img)AV.mine="c";else if(SVG[k])AV.mine=k;else{AV.mine=AV.ids[Math.floor(Math.random()*AV.ids.length)];wr("ls_av",AV.mine)}
})();
const mineSrc=()=>AV.mine==="c"?AV.img:window.avSrc(AV.mine);
/** Shows the current picture in every avatar button (header, game-only screen, login row) */
AV.paint=()=>{document.querySelectorAll(".avme").forEach(b=>{const i=b.querySelector("img");if(i)i.src=mineSrc()});
  document.querySelectorAll("#lav button[data-k]").forEach(b=>b.classList.toggle("sel",b.dataset.k===AV.mine))};
/** Tells the host which picture is mine (call after signing in) */
AV.sync=async()=>{
  try{
    if(AV.mine==="c"&&AV.img){
      const blob=await (await fetch(AV.img)).blob();
      const r=await J("/api/avatar?k=c",{method:"POST",headers:{"Content-Type":blob.type||"image/png"},body:blob});
      if(r.ok)return;toast(r.d.error||"Could not send your picture");AV.mine=AV.ids[0];wr("ls_av",AV.mine);AV.paint();
    }
    await J("/api/avatar?k="+AV.mine,{method:"POST"});
  }catch(e){}
};
AV.set=k=>{AV.mine=k;wr("ls_av",k);AV.paint();if(ME&&ME.name)AV.sync()};

/* ---- editor: pick a photo, move and zoom it, choose a shape ---- */
const SHAPES={
 circle:["Circle",(c,s)=>{c.arc(s/2,s/2,s/2,0,7)}],
 round:["Rounded",(c,s)=>{const r=s*.22;c.moveTo(r,0);c.arcTo(s,0,s,s,r);c.arcTo(s,s,0,s,r);c.arcTo(0,s,0,0,r);c.arcTo(0,0,s,0,r);c.closePath()}],
 squircle:["Squircle",(c,s)=>{for(let i=0;i<=120;i++){const t=i/120*Math.PI*2,co=Math.cos(t),si=Math.sin(t),x=s/2+Math.sign(co)*Math.pow(Math.abs(co),.5)*s/2,y=s/2+Math.sign(si)*Math.pow(Math.abs(si),.5)*s/2;i?c.lineTo(x,y):c.moveTo(x,y)}c.closePath()}],
 hex:["Hexagon",(c,s)=>{for(let i=0;i<6;i++){const a=Math.PI/3*i+Math.PI/6,x=s/2+Math.cos(a)*s/2,y=s/2+Math.sin(a)*s/2;i?c.lineTo(x,y):c.moveTo(x,y)}c.closePath()}],
 heart:["Heart",(c,s)=>{const k=s/100;c.moveTo(50*k,92*k);c.bezierCurveTo(8*k,62*k,0,34*k,14*k,18*k);c.bezierCurveTo(28*k,2*k,46*k,10*k,50*k,26*k);c.bezierCurveTo(54*k,10*k,72*k,2*k,86*k,18*k);c.bezierCurveTo(100*k,34*k,92*k,62*k,50*k,92*k);c.closePath()}],
 star:["Star",(c,s)=>{for(let i=0;i<10;i++){const a=-Math.PI/2+Math.PI/5*i,r=i%2?s*.23:s*.5,x=s/2+Math.cos(a)*r,y=s/2+Math.sin(a)*r;i?c.lineTo(x,y):c.moveTo(x,y)}c.closePath()}],
 diamond:["Diamond",(c,s)=>{c.moveTo(s/2,0);c.lineTo(s,s/2);c.lineTo(s/2,s);c.lineTo(0,s/2);c.closePath()}]
};
function renderTo(cv,size,st){
  const c=cv.getContext("2d");cv.width=cv.height=size;c.clearRect(0,0,size,size);
  if(!st.img)return;
  const k=size/220;c.save();c.beginPath();SHAPES[st.shape][1](c,size);c.clip();
  const iw=st.img.naturalWidth||st.img.width,ih=st.img.naturalHeight||st.img.height,base=220/Math.min(iw,ih),sc=base*st.zoom*k;
  c.drawImage(st.img,size/2-iw*sc/2+st.x*k,size/2-ih*sc/2+st.y*k,iw*sc,ih*sc);c.restore();
}
function exportBlob(st){
  const cv=document.createElement("canvas"),steps=[[128,.86],[128,.7],[104,.7],[96,.55],[72,.55]];
  return new Promise(async res=>{
    for(const[sz,q]of steps){
      renderTo(cv,sz,st);
      const b=await new Promise(r=>cv.toBlob(r,"image/webp",q));
      if(b&&b.type==="image/webp"&&b.size<=AV.MAX_OUT)return res(b);
      if(b&&b.type!=="image/webp"){ // no WebP here: PNG, shrinking until it is small enough
        for(const s2 of[96,80,64,48]){renderTo(cv,s2,st);const p=await new Promise(r=>cv.toBlob(r,"image/png"));if(p&&p.size<=AV.MAX_OUT)return res(p)}
        return res(null);
      }
    }
    res(null);
  });
}
const toDataURL=b=>new Promise(r=>{const f=new FileReader();f.onload=()=>r(f.result);f.readAsDataURL(b)});

AV.edit=()=>{
  const st={img:null,x:0,y:0,zoom:1,shape:"circle"};
  const html='<h3>Your picture</h3><p>Pick a cat or dog, or use your own photo.</p><div id="avp" class="avgrid"></div>'+
   '<div class="avbox"><canvas id="avc" width="220" height="220" aria-label="Preview. Drag to move the photo."></canvas><div class="avhint" id="avh">Choose a photo</div></div>'+
   '<div class="avrow"><button class="btn" id="avf">Photo from this device</button><button class="btn" id="avcam">Take a photo</button></div>'+
   '<input type="file" id="avfile" accept="image/*" hidden><input type="file" id="avcap" accept="image/*" capture="user" hidden>'+
   '<label class="avz">Zoom <input type="range" id="avzoom" min="1" max="4" step="0.01" value="1"></label>'+
   '<div class="avshapes" id="avs"></div><div class="err" id="ave" role="alert"></div>'+
   '<div class="acts"><button class="btn" id="avx">Cancel</button><button class="btn pri" id="avok" disabled>Use this photo</button></div>';
  dialog(html,(d,close)=>{
    const cv=d.querySelector("#avc"),err=d.querySelector("#ave"),ok=d.querySelector("#avok"),hint=d.querySelector("#avh");
    const draw=()=>{renderTo(cv,220,st)};
    d.querySelector("#avp").innerHTML=AV.ids.map(k=>`<button class="avp${AV.mine===k?" sel":""}" data-k="${k}" aria-label="Cat or dog ${k.slice(1)}"><img alt="" src="${window.avSrc(k)}"></button>`).join("");
    d.querySelectorAll("#avp button").forEach(b=>b.onclick=()=>{AV.set(b.dataset.k);close()});
    d.querySelector("#avs").innerHTML=Object.keys(SHAPES).map(k=>`<button class="chip${k===st.shape?" sel":""}" data-s="${k}">${SHAPES[k][0]}</button>`).join("");
    d.querySelectorAll("#avs button").forEach(b=>b.onclick=()=>{st.shape=b.dataset.s;d.querySelectorAll("#avs button").forEach(x=>x.classList.toggle("sel",x===b));draw()});
    if(!matchMedia("(pointer:coarse)").matches)d.querySelector("#avcam").classList.add("hide");
    const pick=f=>{
      err.textContent="";if(!f)return;
      if(!/^image\//.test(f.type)){err.textContent="That is not a picture.";return}
      if(f.size>AV.MAX_SRC){err.textContent="That photo is too big (8 MB at most). Pick a smaller one.";return}
      const u=URL.createObjectURL(f),im=new Image();
      im.onload=()=>{st.img=im;st.x=st.y=0;st.zoom=1;d.querySelector("#avzoom").value=1;hint.classList.add("hide");ok.disabled=false;draw();URL.revokeObjectURL(u)};
      im.onerror=()=>{err.textContent="Could not open that picture.";URL.revokeObjectURL(u)};im.src=u;
    };
    d.querySelector("#avf").onclick=()=>d.querySelector("#avfile").click();d.querySelector("#avcam").onclick=()=>d.querySelector("#avcap").click();
    d.querySelector("#avfile").onchange=e=>pick(e.target.files[0]);d.querySelector("#avcap").onchange=e=>pick(e.target.files[0]);
    d.querySelector("#avzoom").oninput=e=>{st.zoom=+e.target.value;draw()};
    cv.addEventListener("wheel",e=>{if(!st.img)return;e.preventDefault();st.zoom=Math.max(1,Math.min(4,st.zoom*(e.deltaY<0?1.06:.94)));d.querySelector("#avzoom").value=st.zoom;draw()},{passive:false});
    let drag=null;
    cv.onpointerdown=e=>{if(!st.img)return;cv.setPointerCapture(e.pointerId);drag={x:e.clientX,y:e.clientY,ox:st.x,oy:st.y}};
    cv.onpointermove=e=>{if(!drag)return;const s=220/cv.getBoundingClientRect().width;st.x=drag.ox+(e.clientX-drag.x)*s;st.y=drag.oy+(e.clientY-drag.y)*s;draw()};
    cv.onpointerup=cv.onpointercancel=()=>{drag=null};
    d.querySelector("#avx").onclick=close;
    ok.onclick=async()=>{
      ok.disabled=true;err.textContent="";
      const b=await exportBlob(st);
      if(!b){err.textContent="That picture could not be made small enough. Try another one.";ok.disabled=false;return}
      AV.img=await toDataURL(b);wr("ls_avimg",AV.img);AV.mine="c";wr("ls_av","c");AV.paint();if(ME&&ME.name)AV.sync();close();toast("Picture updated");
    };
  });
};
document.querySelectorAll(".avme").forEach(b=>b.onclick=AV.edit);
const lav=document.querySelector("#lav");
if(lav){lav.innerHTML=AV.ids.map(k=>`<button type="button" data-k="${k}" aria-label="Cat or dog ${k.slice(1)}"><img alt="" src="${window.avSrc(k)}"></button>`).join("")+'<button type="button" id="lavmore" aria-label="Use my own photo">+</button>';
  lav.querySelectorAll("button[data-k]").forEach(b=>b.onclick=()=>AV.set(b.dataset.k));document.querySelector("#lavmore").onclick=AV.edit}
AV.paint();
})();

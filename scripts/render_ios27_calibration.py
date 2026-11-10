"""Scientific comparison: recovered Figma optical map vs production CPU map.

This is a reconstruction from coordinate probes, not a Figma screenshot of
the grid. It excludes light, tint, frost, dispersion and GPU rounding.
"""
import json
import math
from array import array
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from calibrate_ios27_glass import field, prediction, previous_prediction, production_params

DIRECTORY = Path('.tmp-glass-calibration')
font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf',15)
small_font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf',12)
source = Image.new('RGB',(480,240),(24,24,24))
draw = ImageDraw.Draw(source)
for x in range(0,480,12):
    draw.line((x,0,x,240),fill=(74,74,74),width=1)
for y in range(0,240,12):
    draw.line((0,y,480,y),fill=(74,74,74),width=1)
for y in [102,128]:
    draw.text((110,y),'REFRACTION 27 / 0123456789',font=font,fill=(245,245,245))
draw.line((0,65,480,175),fill=(100,185,230),width=2)
draw.line((0,175,480,65),fill=(230,175,100),width=2)


def sample(x,y):
    x,y=max(0,min(478.999,x)),max(0,min(238.999,y))
    ix,iy=int(x),int(y);fx,fy=x-ix,y-iy
    p=[source.getpixel((ix,iy)),source.getpixel((ix+1,iy)),
       source.getpixel((ix,iy+1)),source.getpixel((ix+1,iy+1))]
    return tuple(round(p[0][c]*(1-fx)*(1-fy)+p[1][c]*fx*(1-fy)+
                       p[2][c]*(1-fx)*fy+p[3][c]*fx*fy) for c in range(3))


cases=[('Capsule 286 x 48',1,286,48),('Circle 48 x 48',2,48,48),('Thin capsule 110 x 19',3,110,19)]
params=production_params([(w,h,1) for name,index,w,h in cases])
canvas=Image.new('RGB',(1480,430),'#171717')
d=ImageDraw.Draw(canvas)
for col,title in enumerate(['Recovered Figma map','New production fit','Previous outward profile']):
    d.text((col*490+10,8),title,font=font,fill='white')
for row,((name,index,w,h),param) in enumerate(zip(cases,params)):
    reference=Image.open(DIRECTORY/f'chart-{index}.png').convert('RGB')
    pixels=reference.load()
    integrals=[]
    stride=reference.width+1
    for channel in [0,1]:
        integral=array('d',[0])*(stride*(reference.height+1))
        for iy in range(reference.height):
            total=0
            for ix in range(reference.width):
                total+=pixels[ix,iy][channel]
                integral[(iy+1)*stride+ix+1]=integral[iy*stride+ix+1]+total
        integrals.append(integral)
    def average(ix,iy,channel):
        x0,y0,x1,y1=ix-2,iy-2,ix+3,iy+3
        integral=integrals[channel]
        return (integral[y1*stride+x1]-integral[y0*stride+x1]-integral[y1*stride+x0]+integral[y0*stride+x0])/25
    for col in range(3):
        image=source.copy()
        out=image.load()
        for y in range(80,160):
            for x in range(480):
                lx,ly=x+.5-240,y+.5-120
                sd,nx,ny=field(lx,ly,w/2,h/2)
                if sd>=-.5:continue
                if col==0:
                    ix,iy=x*2,y*2
                    dx=(average(ix,iy,0)-average(959-ix,iy,0))/2*480/255-lx
                    dy=(average(ix,iy,1)-average(ix,479-iy,1))/2*240/255-ly
                else:
                    point=(lx,ly,-sd,nx,ny,0,0)
                    dx,dy=prediction(point,param) if col==1 else previous_prediction(point,h)
                out[x,y]=sample(x+dx,y+dy)
        ox,oy=col*490+8,50+row*125
        canvas.paste(image.crop((0,80,480,160)),(ox,oy))
        d.text((ox,oy+83),name,font=small_font,fill='#cccccc')
d.text((8,414),'Optics only. Left: 8-bit probe reconstruction; not a direct Figma grid render.',font=small_font,fill='#bbbbbb')
canvas.save(DIRECTORY/'grid-comparison.png')
print(DIRECTORY/'grid-comparison.png')

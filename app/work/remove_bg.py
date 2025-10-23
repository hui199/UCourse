from PIL import Image, ImageStat
import sys

src = '/Users/hui/pku/or/CourseAssistant/code/375f99e5ac96513cc5aa1b856392641b.jpg'
# output path
out = '/Users/hui/pku/or/CourseAssistant/code/app/work/ic_launcher_foreground_transparent.png'

im = Image.open(src).convert('RGBA')
# sample border area to guess background color (corners)
w,h = im.size
samples = []
for x in range(0, int(w*0.1)):
    for y in range(0, int(h*0.1)):
        samples.append(im.getpixel((x,y)))
for x in range(w-1, w-int(w*0.1)-1, -1):
    for y in range(h-1, h-int(h*0.1)-1, -1):
        samples.append(im.getpixel((x,y)))
# average color
avg = tuple(int(sum(c)/len(c)) for c in zip(*samples))
print('guessed background avg RGBA:', avg)

# create mask by distance from avg color in RGB space
bg_rgb = avg[:3]
threshold = 60  # adjustable
mask = Image.new('L', (w,h), 0)
px = mask.load()
srcpx = im.load()
for i in range(w):
    for j in range(h):
        r,g,b,a = srcpx[i,j]
        dist = ((r-bg_rgb[0])**2 + (g-bg_rgb[1])**2 + (b-bg_rgb[2])**2)**0.5
        if dist > threshold:
            px[i,j] = 255
        else:
            px[i,j] = 0

# refine mask by simple dilation/erosion to reduce edge noise
# apply mask to alpha (no additional ImageFilter available here)
res = Image.new('RGBA', (w,h), (0,0,0,0))
res_px = res.load()
for i in range(w):
    for j in range(h):
        r,g,b,a = srcpx[i,j]
        alpha = mask.getpixel((i,j))
        res_px[i,j] = (r,g,b, alpha)

res.save(out)
print('saved', out)

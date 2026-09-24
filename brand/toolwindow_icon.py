# Monochrome icon generator for the tool window and run configuration.
# Usage: python3 toolwindow_icon.py <out.svg> <color> <size px> right
import math, sys
def svg(color, size, eye, trunk=True):
    S=64
    P=[]
    cx,cy=505,520
    for ang in (-150,-120,-90,-60,-30):
        a=math.radians(ang); ux,uy=math.cos(a),math.sin(a); vx,vy=-uy,ux
        r0,r1,w0,w1=380,495,62,50
        pts=[(cx+ux*r0+vx*w0,cy+uy*r0+vy*w0),(cx+ux*r1+vx*w1,cy+uy*r1+vy*w1),
             (cx+ux*r1-vx*w1,cy+uy*r1-vy*w1),(cx+ux*r0-vx*w0,cy+uy*r0-vy*w0)]
        P.append('<path d="M%s Z" fill="%s"/>' % (' L'.join('%.0f %.0f'%p for p in pts), color))
    P.append('<ellipse cx="500" cy="535" rx="360" ry="410" fill="none" stroke="%s" stroke-width="%d"/>' % (color,S))
    def circ(x,y,r): return "M%d %d a%d %d 0 1 0 %d 0 a%d %d 0 1 0 %d 0 Z"%(x-r,y,r,r,2*r,r,r,-2*r)
    face="M280 610 L280 515 Q280 368 515 368 Q750 368 750 515 L750 610 Q750 650 710 650 L320 650 Q280 650 280 610 Z"
    ex=(395,640); ey=515; er=88; pr=42
    P.append('<path fill-rule="evenodd" d="%s %s %s" fill="%s"/>' % (face, circ(ex[0],ey,er), circ(ex[1],ey,er), color))
    P.append('<circle cx="%d" cy="%d" r="%d" fill="%s"/><circle cx="%d" cy="%d" r="%d" fill="%s"/>' % (ex[0]+10,ey+8,pr,color,ex[1]-10,ey+8,pr,color))
    arc={'left':"M440 262 Q488 205 536 262",'right':"M600 290 Q648 233 696 290"}[eye]
    P.append('<path d="%s" fill="none" stroke="%s" stroke-width="%d" stroke-linecap="round"/>' % (arc,color,S-8))
    if trunk:
        # Trunk implied by two strokes: inner edge curving down from the face corner, then a short cut
        # to the body outline. The body outline itself is the trunk's outer edge.
        P.append('<path d="M742 640 C742 725 690 768 617 775 L672 872" fill="none" stroke="%s" stroke-width="%d" stroke-linecap="round" stroke-linejoin="round"/>' % (color,S-8))
    return '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 1024 1024">\n  %s\n</svg>\n' % (size,size,'\n  '.join(P))
if __name__=="__main__":
    open(sys.argv[1],'w').write(svg(sys.argv[2],int(sys.argv[3]),sys.argv[4]))

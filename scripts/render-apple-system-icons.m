// Development-only native asset capture. No Apple private API runs on Android.
#import <AppKit/AppKit.h>
#import <ImageIO/ImageIO.h>
#import <objc/runtime.h>
#import <dlfcn.h>

@interface NSObject (AppleIconCapture)
- (id)initWithURL:(NSURL *)url error:(NSError **)error;
- (id)iconLayerStackWithName:(NSString *)name scaleFactor:(double)scale
               deviceIdiom:(long long)idiom deviceSubtype:(unsigned long long)subtype
              displayGamut:(long long)gamut appearanceName:(NSString *)appearance locale:(id)locale;
- (id)_IF_ImageWithSize:(CGSize)size scale:(NSInteger)scale platform:(long long)platform
            appearance:(long long)appearance appearanceVariant:(long long)variant
             tintColor:(id)tint encapsulationShape:(id)shape;
- (CGImageRef)CGImage;
@end

int main(int argc, const char **argv) {
  @autoreleasepool {
    if (argc != 7) {
      fprintf(stderr, "usage: capture Assets.car iconName ios|macos output-dir prefix ios-source|macos-source\n");
      return 2;
    }
    dlopen("/System/Library/PrivateFrameworks/CoreUI.framework/CoreUI", RTLD_NOW);
    dlopen("/System/Library/PrivateFrameworks/IconRendering.framework/IconRendering", RTLD_NOW);
    NSError *error = nil;
    id catalog = [[NSClassFromString(@"CUICatalog") alloc]
      initWithURL:[NSURL fileURLWithPath:@(argv[1])] error:&error];
    if (!catalog) { NSLog(@"%@", error); return 3; }
    NSString *directory = @(argv[4]);
    [[NSFileManager defaultManager] createDirectoryAtPath:directory
      withIntermediateDirectories:YES attributes:nil error:&error];
    BOOL iosSource = [@(argv[6]) isEqualToString:@"ios-source"];
    for (int appearance = 1; appearance <= 2; appearance++) {
      NSString *look = iosSource
        ? (appearance == 2 ? @"UIAppearanceDark" : @"UIAppearanceLight")
        : (appearance == 2 ? @"NSAppearanceNameDarkAqua" : @"NSAppearanceNameAqua");
      id stack = [catalog iconLayerStackWithName:@(argv[2]) scaleFactor:1 deviceIdiom:0
        deviceSubtype:0 displayGamut:1 appearanceName:look locale:nil];
      id image = [stack _IF_ImageWithSize:CGSizeMake(1024, 1024) scale:1
        platform:[@(argv[3]) isEqualToString:@"macos"] ? 1 : 2
        appearance:appearance appearanceVariant:0 tintColor:nil encapsulationShape:nil];
      if (!image) { fprintf(stderr, "native layered icon unavailable\n"); return 4; }
      // Canonical Android static target: 8-bit sRGB. WebP packing is lossless against these pixels.
      CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
      CGContextRef bitmap = CGBitmapContextCreate(NULL, 1024, 1024, 8, 4096, space,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
      CGColorSpaceRelease(space);
      CGContextDrawImage(bitmap, CGRectMake(0, 0, 1024, 1024), [image CGImage]);
      CGImageRef rendered = CGBitmapContextCreateImage(bitmap);
      NSString *name = [NSString stringWithFormat:@"%s-%s-%s.png", argv[5], argv[3],
        appearance == 2 ? "dark" : "light"];
      NSURL *url = [NSURL fileURLWithPath:[directory stringByAppendingPathComponent:name]];
      CGImageDestinationRef destination = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)url, CFSTR("public.png"), 1, NULL);
      if (!destination) return 5;
      CGImageDestinationAddImage(destination, rendered, NULL);
      BOOL ok = CGImageDestinationFinalize(destination);
      CFRelease(destination);
      CGImageRelease(rendered);
      CGContextRelease(bitmap);
      if (!ok) return 6;
      printf("%s\n", url.path.UTF8String);
    }
  }
  return 0;
}

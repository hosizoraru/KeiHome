// Development-only CoreUI capture; no Apple private API runs on Android.
#import <AppKit/AppKit.h>
#import <ImageIO/ImageIO.h>
#import <dlfcn.h>

@interface NSObject (AppleDynamicCapture)
- (id)initWithURL:(NSURL *)url error:(NSError **)error;
- (id)iconLayerStackWithName:(NSString *)name scaleFactor:(double)scale
               deviceIdiom:(long long)idiom deviceSubtype:(unsigned long long)subtype
              displayGamut:(long long)gamut appearanceName:(NSString *)appearance locale:(id)locale;
- (id)_IF_ImageWithSize:(CGSize)size scale:(NSInteger)scale platform:(long long)platform
            appearance:(long long)appearance appearanceVariant:(long long)variant
             tintColor:(id)tint encapsulationShape:(id)shape;
- (CGImageRef)CGImage;
- (CGImageRef)rasterizeImageUsingScaleFactor:(double)scale forTargetSize:(CGSize)size;
@end

static BOOL writeImage(CGImageRef source, NSString *path) {
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(NULL, 1024, 1024, 8, 4096, space,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(space);
    if (!context || !source) return NO;
    CGContextDrawImage(context, CGRectMake(0, 0, 1024, 1024), source);
    CGImageRef image = CGBitmapContextCreateImage(context);
    CGImageDestinationRef destination = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)[NSURL fileURLWithPath:path], CFSTR("public.png"), 1, NULL);
    CGImageDestinationAddImage(destination, image, NULL);
    BOOL success = CGImageDestinationFinalize(destination);
    CFRelease(destination);
    CGImageRelease(image);
    CGContextRelease(context);
    return success;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc != 5) return 2; // catalog, output, calendar|clock, ios|ipados
        BOOL clock = [@(argv[3]) isEqualToString:@"clock"];
        BOOL pad = [@(argv[4]) isEqualToString:@"ipados"];
        if (!clock && ![@(argv[3]) isEqualToString:@"calendar"]) return 2;
        if (!pad && ![@(argv[4]) isEqualToString:@"ios"]) return 2;
        dlopen("/System/Library/PrivateFrameworks/CoreUI.framework/CoreUI", RTLD_NOW);
        dlopen("/System/Library/PrivateFrameworks/IconRendering.framework/IconRendering", RTLD_NOW);
        NSError *error = nil;
        id catalog = [[NSClassFromString(@"CUICatalog") alloc]
            initWithURL:[NSURL fileURLWithPath:@(argv[1])] error:&error];
        if (!catalog || error) return 3;
        [[NSFileManager defaultManager] createDirectoryAtPath:@(argv[2])
            withIntermediateDirectories:YES attributes:nil error:&error];
        if (error) return 4;
        NSDictionary *groups = @{@"hour": @"Hour", @"minute": @"Minutes", @"second": @"Seconds"};
        for (int dark = 0; dark < 2; dark++) {
            id original = [catalog iconLayerStackWithName:@"AppIcon" scaleFactor:1
                deviceIdiom:pad ? 1 : 0 deviceSubtype:0 displayGamut:1
                appearanceName:dark ? @"UIAppearanceDark" : @"UIAppearanceLight" locale:nil];
            if (!original) return 5;
            NSArray *parts = clock ? @[@"base", @"hour", @"minute", @"second"] : @[@"base"];
            for (NSString *part in parts) {
                id stack = [original mutableCopy];
                NSMutableArray *layers = [NSMutableArray array];
                for (id layer in [stack valueForKey:@"layers"]) {
                    NSString *name = [layer valueForKey:@"name"];
                    BOOL hand = [name hasSuffix:@"/Hour"] || [name hasSuffix:@"/Minutes"]
                        || [name hasSuffix:@"/Seconds"];
                    BOOL keep = [part isEqualToString:@"base"]
                        ? (clock ? !hand : [layer isKindOfClass:NSClassFromString(@"CUINamedGradient")])
                        : [name hasSuffix:[@"/" stringByAppendingString:groups[part]]];
                    if (keep) [layers addObject:layer];
                }
                if (layers.count == 0) return 6;
                [stack setValue:layers forKey:@"layers"];
                id image = [stack _IF_ImageWithSize:CGSizeMake(1024, 1024) scale:1
                    platform:pad ? 5 : 2 appearance:dark appearanceVariant:0
                    tintColor:nil encapsulationShape:nil];
                NSString *leaf = [NSString stringWithFormat:@"%@-%@.png", part, dark ? @"dark" : @"light"];
                if (!image || !writeImage([image CGImage], [@(argv[2]) stringByAppendingPathComponent:leaf])) return 7;
                if (![part isEqualToString:@"base"]) {
                    id group = layers.firstObject;
                    NSArray *children = [group valueForKey:@"layers"];
                    if (children.count != 1) return 8;
                    CGImageRef mask = [children.firstObject rasterizeImageUsingScaleFactor:1
                        forTargetSize:CGSizeMake(1024, 1024)];
                    if (!writeImage(mask, [@(argv[2]) stringByAppendingPathComponent:
                        [@"mask-" stringByAppendingString:leaf]])) return 9;
                }
            }
        }
    }
    return 0;
}

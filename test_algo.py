
def user_algorithm(N):
    S = N // 4
    sheets = []
    print(f"User Algorithm (N={N}):")
    for i in range(1, S + 1):
        fl = N + 1 - (2 * i - 1)
        fr = 2 * i - 1
        bl = 2 * i
        br = N + 1 - 2 * i
        print(f"Sheet {i}: FL={fl}, FR={fr}, BL={bl}, BR={br}")
        sheets.append(((fl, fr), (bl, br)))
    return sheets

def my_algorithm(N):
    totalBookletPages = N
    totalSheets = N // 4
    print(f"\nMy Algorithm (N={N}):")
    for sheetIndex in range(totalSheets):
        # Front
        # l = totalBookletPages - 1 - 2 * sheetIndex
        # r = 2 * sheetIndex
        # (These are 0-based indices, convert to 1-based for comparison)
        fl_idx = totalBookletPages - 1 - 2 * sheetIndex
        fr_idx = 2 * sheetIndex
        
        fl = fl_idx + 1
        fr = fr_idx + 1
        
        # Back
        # l = 2 * sheetIndex + 1 
        # r = totalBookletPages - 2 - 2 * sheetIndex
        bl_idx = 2 * sheetIndex + 1
        br_idx = totalBookletPages - 2 - 2 * sheetIndex
        
        bl = bl_idx + 1
        br = br_idx + 1
        
        print(f"Sheet {sheetIndex+1}: FL={fl}, FR={fr}, BL={bl}, BR={br}")

user_algorithm(8)
my_algorithm(8)

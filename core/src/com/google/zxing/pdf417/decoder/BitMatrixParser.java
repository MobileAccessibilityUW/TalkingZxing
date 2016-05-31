/*
 * Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.pdf417.decoder;

import com.google.zxing.ReaderException;
import com.google.zxing.common.BitMatrix;

/**
 * <p>
 * This class parses the BitMatrix image into codewords.
 * </p>
 *
 * @author SITA Lab (kevin.osullivan@sita.aero)
 */
final class BitMatrixParser {

  private static final int MAX_ROW_DIFFERENCE = 6;
  private static final int MAX_ROWS = 90;
  //private static final int MAX_COLUMNS = 30;
  // Maximum Codewords (Data + Error)
  private static final int MAX_CW_CAPACITY = 929;
  private static final int MODULES_IN_SYMBOL = 17;

  private final BitMatrix bitMatrix;
  private int rows = 0;
  //private int columns = 0;

  private int leftColumnECData = 0;
  private int rightColumnECData = 0;
  private int eraseCount = 0;
  private int[] erasures = null;
  private int ecLevel = -1;

  BitMatrixParser(BitMatrix bitMatrix) {
    this.bitMatrix = bitMatrix;
  }

  /**
   * To ensure separability of rows, codewords of consecutive rows belong to
   * different subsets of all possible codewords. This routine scans the
   * symbols in the barcode. When it finds a number of consecutive rows which
   * are the same, it assumes that this is a row of codewords and processes
   * them into a codeword array.
   *
   * @return an array of codewords.
   */
  int[] readCodewords() {
    int width = bitMatrix.getDimension();
    // TODO should be a rectangular matrix
    int height = width;

    erasures = new int[MAX_CW_CAPACITY];

    // Get the number of pixels in a module across the X dimension
    //float moduleWidth = bitMatrix.getModuleWidth();
    float moduleWidth = 1.0f; // Image has been sampled and reduced

    int[] rowCounters = new int[width];
    int[] codewords = new int[MAX_CW_CAPACITY];
    int next = 0;
    int matchingConsecutiveScans = 0;
    boolean rowInProgress = false;
    int rowNumber = 0;
    int rowHeight = 0;
    for (int i = 1; i < height; i++) {
      if (rowNumber >= MAX_ROWS) {
        // Something is wrong, since we have exceeded
        // the maximum rows in the specification.
        // TODO Maybe return error code
        return null;
      }
      int rowDifference = 0;
      // Scan a line of modules and check the
      // difference between this and the previous line
      for (int j = 0; j < width; j++) {
        // Accumulate differences between this line and the
        // previous line.
        if (bitMatrix.get(j, i) != bitMatrix.get(j, i - 1)) {
          rowDifference++;
        }
      }
      if (rowDifference <= moduleWidth * MAX_ROW_DIFFERENCE) {
        for (int j = 0; j < width; j++) {
          // Accumulate the black pixels on this line
          if (bitMatrix.get(j, i)) {
            rowCounters[j]++;
          }
        }
        // Increment the number of consecutive rows of pixels
        // that are more or less the same
        matchingConsecutiveScans++;
        // Height of a row is a multiple of the module size in pixels
        // Usually at least 3 times the module size
        if (matchingConsecutiveScans >= moduleWidth * 2) { // MGMG
          // We have some previous matches as well as a match here
          // Set processing a unique row.
          rowInProgress = true;
        }
      } else {
        if (rowInProgress) {
          // Process Row
          next = processRow(rowCounters, rowNumber, rowHeight, codewords, next);
          if (next == -1) {
            // Something is wrong, since we have exceeded
            // the maximum columns in the specification.
            // TODO Maybe return error code
            return null;
          }
          // Reinitialize the row counters.
          for (int j = 0; j < rowCounters.length; j++) {
            rowCounters[j] = 0;
          }
          rowNumber++;
          rowHeight = 0;
        }
        matchingConsecutiveScans = 0;
        rowInProgress = false;
      }
      rowHeight++;
    }
    // Check for a row that was in progress before we exited above.
    if (rowInProgress) {
      // Process Row
      if (rowNumber >= MAX_ROWS) {
        // Something is wrong, since we have exceeded
        // the maximum rows in the specification.
        // TODO Maybe return error code
        return null;
      }
      next = processRow(rowCounters, rowNumber, rowHeight, codewords, next);
      rowNumber++;
      rows = rowNumber;
    }
    erasures = trimArray(erasures, eraseCount);
    return trimArray(codewords, next);
  }

  /**
   * Trim the array to the required size.
   *
   * @param array the array
   * @param size  the size to trim it to
   * @return the new trimmed array
   */
  private static int[] trimArray(int[] array, int size) {
    if (size > 0) {
      int[] a = new int[size];
      for (int i = 0; i < size; i++) {
        a[i] = array[i];
      }
      return a;
    } else {
      return null;
    }
  }

  /**
   * Convert the symbols in the row to codewords.
   * Each PDF417 symbol character consists of four bar elements and four space
   * elements, each of which can be one to six modules wide. The four bar and
   * four space elements shall measure 17 modules in total.
   *
   * @param rowCounters an array containing the counts of black pixels for each column
   *                    in the row.
   * @param rowNumber   the current row number of codewords.
   * @param rowHeight   the height of this row in pixels.
   * @param codewords   the codeword array to save codewords into.
   * @param next        the next available index into the codewords array.
   * @return the next available index into the codeword array after processing
   *         this row.
   */
  int processRow(int[] rowCounters, int rowNumber, int rowHeight, int[] codewords, int next) {
    int width = bitMatrix.getDimension();
    int columnNumber = 0;
    long symbol = 0;
    for (int i = 0; i < width; i += MODULES_IN_SYMBOL) {
      for (int mask = MODULES_IN_SYMBOL - 1; mask >= 0; mask--) {
        if (rowCounters[i + (MODULES_IN_SYMBOL - 1 - mask)] >= rowHeight >>> 1) {
          symbol |= 1L << mask;
        }
      }
      if (columnNumber > 0) {
        int cw = getCodeword(symbol);
        // if (debug) System.out.println(" " + Long.toBinaryString(symbol) +
        // " cw=" +cw + " ColumnNumber=" +columnNumber + "i=" +i);
        if (cw < 0 && i < width - MODULES_IN_SYMBOL) {
          // Skip errors on the Right row indicator column
          erasures[eraseCount] = next;
          next++;
          eraseCount++;
        } else {
          codewords[next++] = cw;
        }
      } else {
        // Left row indicator column
        int cw = getCodeword(symbol);
        // if (debug) System.out.println(" " + Long.toBinaryString(symbol) +
        // " cw=" +cw + " ColumnNumber=" +columnNumber + "i=" +i);
        if (ecLevel < 0) {
          switch (rowNumber % 3) {
            case 0:
              break;
            case 1:
              leftColumnECData = cw;
              break;
            case 2:
              break;
          }
        }
      }
      symbol = 0;
      //columns = columnNumber;
      columnNumber++;
    }
    if (columnNumber > 1) {
      // Right row indicator column is in codeword[next]
      //columns--;
      // Overwrite the last codeword i.e. Right Row Indicator
      --next;
      if (ecLevel < 0) {
        switch (rowNumber % 3) {
          case 0:
            break;
          case 1:
            break;
          case 2:
            rightColumnECData = codewords[next];
            if (rightColumnECData == leftColumnECData
                && leftColumnECData != 0) {
              ecLevel = ((rightColumnECData % 30) - rows % 3) / 3;
            }
            break;
        }
      }
      codewords[next] = 0;
    }
    return next;
  }

  /**
   * Build a symbol from the pixels.
   * Each symbol character is defined by an 8-digit bar-space sequence which
   * represents the module widths of the eight elements of that symbol
   * character.
   *
   * @param counters  array of pixel counter corresponding to each Bar/Space pattern.
   * @return the symbol
   */
  /*
  private static long getSymbol(int[] counters, float moduleWidth) {
    int pixelsInSymbol = 0;
    for (int j = 0; j < counters.length; j++) {
      pixelsInSymbol += counters[j];
    }
    float avgModuleWidth = (pixelsInSymbol / 17.0f);
    boolean toggle = true;
    int shift = 0;
    int symbol = 0;
    for (int j = 0; j < counters.length; j++) {
      if (counters[j] < moduleWidth && counters[j] > 0) {
        // Give a very narrow bar/space a chance
        counters[j] = (int) moduleWidth;
      }
      // Calculate number of modules in the symbol.
      // int modules = (int)(counters[j]/moduleWidth);
      // int modules = round(counters[j]/moduleWidth);
      int modules = round(counters[j] / avgModuleWidth);
      if (modules > 6) {
        // Maximum size is 6 modules
        modules = 6;
      } else if (modules < 1) {
        modules = 1;
      }
      if (toggle) {
        for (int k = 0; k < modules; k++) {
          symbol |= 1 << (16 - k - shift);
        }
        toggle = false;
      } else {
        toggle = true;
      }
      shift += modules;
    }
    return symbol;
  }
   */

  /**
   * Translate the symbol into a codeword.
   *
   * @param symbol
   * @return the codeword corresponding to the symbol.
   */
  private static int getCodeword(long symbol) {
    long sym = symbol;
    sym &= 0x3ffff;
    int i = findCodewordIndex(sym);
    if (i == -1) {
      return -1;
    } else {
      long cw = CODEWORD_TABLE[i] - 1;
      cw %= 929;
      return (int) cw;
    }
  }

  /**
   * Use a binary search to find the index of the codeword corresponding to
   * this symbol.
   *
   * @param symbol the symbol from the barcode.
   * @return the index into the codeword table.
   */
  private static int findCodewordIndex(long symbol) {
    int first = 0;
    int upto = SYMBOL_TABLE.length;
    while (first < upto) {
      int mid = (first + upto) >>> 1; // Compute mid point.
      if (symbol < SYMBOL_TABLE[mid]) {
        upto = mid; // repeat search in bottom half.
      } else if (symbol > SYMBOL_TABLE[mid]) {
        first = mid + 1; // Repeat search in top half.
      } else {
        return mid; // Found it. return position
      }
    }
    return -1;
    // if (debug) System.out.println("Failed to find codeword for Symbol=" +
    // symbol);
  }

  /**
   * Ends up being a bit faster than Math.round(). This merely rounds its
   * argument to the nearest int, where x.5 rounds up.
   */
  /*
  private static int round(float d) {
    return (int) (d + 0.5f);
  }
   */

  /**
   * Returns an array of locations representing the erasures.
   */
  public int[] getErasures() {
    return erasures;
  }

  public int getECLevel() {
    return ecLevel;
  }

  /**
   * Convert the symbols in the row to codewords.
   * Each PDF417 symbol character consists of four bar elements and four space
   * elements, each of which can be one to six modules wide. The four bar and
   * four space elements shall measure 17 modules in total.
   *
   * @param rowCounters an array containing the counts of black pixels for each column
   *                    in the row.
   * @param rowNumber   the current row number of codewords.
   * @param rowHeight   the height of this row in pixels.
   * @param moduleWidth the size of a module in pixels.
   * @param codewords   the codeword array to save codewords into.
   * @param next        the next available index into the codewords array.
   * @return the next available index into the codeword array after processing
   *         this row.
   * @throws ReaderException
   */
  /*
  int processRow1(int[] rowCounters, int rowNumber, int rowHeight,
                  float moduleWidth, int[] codewords, int next) {
    int width = bitMatrix.getDimension();
    int firstBlack = 0;

    for (firstBlack = 0; firstBlack < width; firstBlack++) {
      // Step forward until we find the first black pixels
      if (rowCounters[firstBlack] >= rowHeight >>> 1) {
        break;
      }
    }

    int[] counters = new int[8];
    int state = 0; // In black pixels, looking for white, first or second time
    long symbol = 0;
    int columnNumber = 0;
    for (int i = firstBlack; i < width; i++) {
      if (state == 1 || state == 3 || state == 5 || state == 7) { // In white
        // pixels,
        // looking
        // for
        // black
        // If more than half the column is black
        if (rowCounters[i] >= rowHeight >>> 1 || i == width - 1) {
          if (i == width - 1) {
            counters[state]++;
          }
          // In black pixels or the end of a row
          state++;
          if (state < 8) {
            // Don't count the last one
            counters[state]++;
          }
        } else {
          counters[state]++;
        }
      } else {
        if (rowCounters[i] < rowHeight >>> 1) {
          // Found white pixels
          state++;
          if (state == 7 && i == width - 1) {
            // Its found white pixels at the end of the row,
            // give it a chance to exit gracefully
            i--;
          } else {
            // Found white pixels
            counters[state]++;
          }
        } else {
          if (state < 8) {
            // Still in black pixels
            counters[state]++;
          }
        }
      }
      if (state == 8) { // Found black, white, black, white, black, white,
        // black, white and stumbled back onto black; done
        if (columnNumber >= MAX_COLUMNS) {
          // Something is wrong, since we have exceeded
          // the maximum columns in the specification.
          // TODO Maybe return error code
          return -1;
        }
        if (columnNumber > 0) {
          symbol = getSymbol(counters, moduleWidth);
          int cw = getCodeword(symbol);
          // if (debug) System.out.println(" " +
          // Long.toBinaryString(symbol) + " cw=" +cw + " ColumnNumber="
          // +columnNumber + "i=" +i);
          if (cw < 0) {
            erasures[eraseCount] = next;
            next++;
            eraseCount++;
          } else {
            codewords[next++] = cw;
          }
        } else {
          // Left row indicator column
          symbol = getSymbol(counters, moduleWidth);
          int cw = getCodeword(symbol);
          if (ecLevel < 0) {
            switch (rowNumber % 3) {
              case 0:
                break;
              case 1:
                leftColumnECData = cw;
                break;
              case 2:
                break;
            }
          }
        }
        // Step back so that this pixel can be examined during the next
        // pass.
        i--;
        counters = new int[8];
        columns = columnNumber;
        columnNumber++;
        // Introduce some errors if (rowNumber == 0 && columnNumber == 4)
        // { codewords[next-1] = 0; erasures[eraseCount] = next-1;
        // eraseCount++; } if (rowNumber == 0 && columnNumber == 6) {
        // codewords[next-1] = 10; erasures[eraseCount] = next-1;
        // eraseCount++; } if (rowNumber == 0 && columnNumber == 8) {
        // codewords[next-1] = 10; erasures[eraseCount] = next-1;
        // eraseCount++; }
        state = 0;
        symbol = 0;
      }
    }
    if (columnNumber > 1) {
      // Right row indicator column is in codeword[next]
      columns--;
      // Overwrite the last codeword i.e. Right Row Indicator
      --next;
      if (ecLevel < 0) {
        switch (rowNumber % 3) {
          case 0:
            break;
          case 1:
            break;
          case 2:
            rightColumnECData = codewords[next];
            if (rightColumnECData == leftColumnECData
                && leftColumnECData != 0) {
              ecLevel = ((rightColumnECData % 30) - rows % 3) / 3;
            }
            break;
        }
      }
      codewords[next] = 0;
    }
    return next;
  }
   */

  /**
   * The sorted table of all possible symbols. Extracted from the PDF417
   * specification. The index of a symbol in this table corresponds to the
   * index into the codeword table.
   */
  private static final int[] SYMBOL_TABLE = {0x1025e, 0x1027a, 0x1029e,
      0x102bc, 0x102f2, 0x102f4, 0x1032e, 0x1034e, 0x1035c, 0x10396,
      0x103a6, 0x103ac, 0x10422, 0x10428, 0x10436, 0x10442, 0x10444,
      0x10448, 0x10450, 0x1045e, 0x10466, 0x1046c, 0x1047a, 0x10482,
      0x1049e, 0x104a0, 0x104bc, 0x104c6, 0x104d8, 0x104ee, 0x104f2,
      0x104f4, 0x10504, 0x10508, 0x10510, 0x1051e, 0x10520, 0x1053c,
      0x10540, 0x10578, 0x10586, 0x1058c, 0x10598, 0x105b0, 0x105be,
      0x105ce, 0x105dc, 0x105e2, 0x105e4, 0x105e8, 0x105f6, 0x1062e,
      0x1064e, 0x1065c, 0x1068e, 0x1069c, 0x106b8, 0x106de, 0x106fa,
      0x10716, 0x10726, 0x1072c, 0x10746, 0x1074c, 0x10758, 0x1076e,
      0x10792, 0x10794, 0x107a2, 0x107a4, 0x107a8, 0x107b6, 0x10822,
      0x10828, 0x10842, 0x10848, 0x10850, 0x1085e, 0x10866, 0x1086c,
      0x1087a, 0x10882, 0x10884, 0x10890, 0x1089e, 0x108a0, 0x108bc,
      0x108c6, 0x108cc, 0x108d8, 0x108ee, 0x108f2, 0x108f4, 0x10902,
      0x10908, 0x1091e, 0x10920, 0x1093c, 0x10940, 0x10978, 0x10986,
      0x10998, 0x109b0, 0x109be, 0x109ce, 0x109dc, 0x109e2, 0x109e4,
      0x109e8, 0x109f6, 0x10a08, 0x10a10, 0x10a1e, 0x10a20, 0x10a3c,
      0x10a40, 0x10a78, 0x10af0, 0x10b06, 0x10b0c, 0x10b18, 0x10b30,
      0x10b3e, 0x10b60, 0x10b7c, 0x10b8e, 0x10b9c, 0x10bb8, 0x10bc2,
      0x10bc4, 0x10bc8, 0x10bd0, 0x10bde, 0x10be6, 0x10bec, 0x10c2e,
      0x10c4e, 0x10c5c, 0x10c62, 0x10c64, 0x10c68, 0x10c76, 0x10c8e,
      0x10c9c, 0x10cb8, 0x10cc2, 0x10cc4, 0x10cc8, 0x10cd0, 0x10cde,
      0x10ce6, 0x10cec, 0x10cfa, 0x10d0e, 0x10d1c, 0x10d38, 0x10d70,
      0x10d7e, 0x10d82, 0x10d84, 0x10d88, 0x10d90, 0x10d9e, 0x10da0,
      0x10dbc, 0x10dc6, 0x10dcc, 0x10dd8, 0x10dee, 0x10df2, 0x10df4,
      0x10e16, 0x10e26, 0x10e2c, 0x10e46, 0x10e58, 0x10e6e, 0x10e86,
      0x10e8c, 0x10e98, 0x10eb0, 0x10ebe, 0x10ece, 0x10edc, 0x10f0a,
      0x10f12, 0x10f14, 0x10f22, 0x10f28, 0x10f36, 0x10f42, 0x10f44,
      0x10f48, 0x10f50, 0x10f5e, 0x10f66, 0x10f6c, 0x10fb2, 0x10fb4,
      0x11022, 0x11028, 0x11042, 0x11048, 0x11050, 0x1105e, 0x1107a,
      0x11082, 0x11084, 0x11090, 0x1109e, 0x110a0, 0x110bc, 0x110c6,
      0x110cc, 0x110d8, 0x110ee, 0x110f2, 0x110f4, 0x11102, 0x1111e,
      0x11120, 0x1113c, 0x11140, 0x11178, 0x11186, 0x11198, 0x111b0,
      0x111be, 0x111ce, 0x111dc, 0x111e2, 0x111e4, 0x111e8, 0x111f6,
      0x11208, 0x1121e, 0x11220, 0x11278, 0x112f0, 0x1130c, 0x11330,
      0x1133e, 0x11360, 0x1137c, 0x1138e, 0x1139c, 0x113b8, 0x113c2,
      0x113c8, 0x113d0, 0x113de, 0x113e6, 0x113ec, 0x11408, 0x11410,
      0x1141e, 0x11420, 0x1143c, 0x11440, 0x11478, 0x114f0, 0x115e0,
      0x1160c, 0x11618, 0x11630, 0x1163e, 0x11660, 0x1167c, 0x116c0,
      0x116f8, 0x1171c, 0x11738, 0x11770, 0x1177e, 0x11782, 0x11784,
      0x11788, 0x11790, 0x1179e, 0x117a0, 0x117bc, 0x117c6, 0x117cc,
      0x117d8, 0x117ee, 0x1182e, 0x11834, 0x1184e, 0x1185c, 0x11862,
      0x11864, 0x11868, 0x11876, 0x1188e, 0x1189c, 0x118b8, 0x118c2,
      0x118c8, 0x118d0, 0x118de, 0x118e6, 0x118ec, 0x118fa, 0x1190e,
      0x1191c, 0x11938, 0x11970, 0x1197e, 0x11982, 0x11984, 0x11990,
      0x1199e, 0x119a0, 0x119bc, 0x119c6, 0x119cc, 0x119d8, 0x119ee,
      0x119f2, 0x119f4, 0x11a0e, 0x11a1c, 0x11a38, 0x11a70, 0x11a7e,
      0x11ae0, 0x11afc, 0x11b08, 0x11b10, 0x11b1e, 0x11b20, 0x11b3c,
      0x11b40, 0x11b78, 0x11b8c, 0x11b98, 0x11bb0, 0x11bbe, 0x11bce,
      0x11bdc, 0x11be2, 0x11be4, 0x11be8, 0x11bf6, 0x11c16, 0x11c26,
      0x11c2c, 0x11c46, 0x11c4c, 0x11c58, 0x11c6e, 0x11c86, 0x11c98,
      0x11cb0, 0x11cbe, 0x11cce, 0x11cdc, 0x11ce2, 0x11ce4, 0x11ce8,
      0x11cf6, 0x11d06, 0x11d0c, 0x11d18, 0x11d30, 0x11d3e, 0x11d60,
      0x11d7c, 0x11d8e, 0x11d9c, 0x11db8, 0x11dc4, 0x11dc8, 0x11dd0,
      0x11dde, 0x11de6, 0x11dec, 0x11dfa, 0x11e0a, 0x11e12, 0x11e14,
      0x11e22, 0x11e24, 0x11e28, 0x11e36, 0x11e42, 0x11e44, 0x11e50,
      0x11e5e, 0x11e66, 0x11e6c, 0x11e82, 0x11e84, 0x11e88, 0x11e90,
      0x11e9e, 0x11ea0, 0x11ebc, 0x11ec6, 0x11ecc, 0x11ed8, 0x11eee,
      0x11f1a, 0x11f2e, 0x11f32, 0x11f34, 0x11f4e, 0x11f5c, 0x11f62,
      0x11f64, 0x11f68, 0x11f76, 0x12048, 0x1205e, 0x12082, 0x12084,
      0x12090, 0x1209e, 0x120a0, 0x120bc, 0x120d8, 0x120f2, 0x120f4,
      0x12108, 0x1211e, 0x12120, 0x1213c, 0x12140, 0x12178, 0x12186,
      0x12198, 0x121b0, 0x121be, 0x121e2, 0x121e4, 0x121e8, 0x121f6,
      0x12204, 0x12210, 0x1221e, 0x12220, 0x12278, 0x122f0, 0x12306,
      0x1230c, 0x12330, 0x1233e, 0x12360, 0x1237c, 0x1238e, 0x1239c,
      0x123b8, 0x123c2, 0x123c8, 0x123d0, 0x123e6, 0x123ec, 0x1241e,
      0x12420, 0x1243c, 0x124f0, 0x125e0, 0x12618, 0x1263e, 0x12660,
      0x1267c, 0x126c0, 0x126f8, 0x12738, 0x12770, 0x1277e, 0x12782,
      0x12784, 0x12790, 0x1279e, 0x127a0, 0x127bc, 0x127c6, 0x127cc,
      0x127d8, 0x127ee, 0x12820, 0x1283c, 0x12840, 0x12878, 0x128f0,
      0x129e0, 0x12bc0, 0x12c18, 0x12c30, 0x12c3e, 0x12c60, 0x12c7c,
      0x12cc0, 0x12cf8, 0x12df0, 0x12e1c, 0x12e38, 0x12e70, 0x12e7e,
      0x12ee0, 0x12efc, 0x12f04, 0x12f08, 0x12f10, 0x12f20, 0x12f3c,
      0x12f40, 0x12f78, 0x12f86, 0x12f8c, 0x12f98, 0x12fb0, 0x12fbe,
      0x12fce, 0x12fdc, 0x1302e, 0x1304e, 0x1305c, 0x13062, 0x13068,
      0x1308e, 0x1309c, 0x130b8, 0x130c2, 0x130c8, 0x130d0, 0x130de,
      0x130ec, 0x130fa, 0x1310e, 0x13138, 0x13170, 0x1317e, 0x13182,
      0x13184, 0x13190, 0x1319e, 0x131a0, 0x131bc, 0x131c6, 0x131cc,
      0x131d8, 0x131f2, 0x131f4, 0x1320e, 0x1321c, 0x13270, 0x1327e,
      0x132e0, 0x132fc, 0x13308, 0x1331e, 0x13320, 0x1333c, 0x13340,
      0x13378, 0x13386, 0x13398, 0x133b0, 0x133be, 0x133ce, 0x133dc,
      0x133e2, 0x133e4, 0x133e8, 0x133f6, 0x1340e, 0x1341c, 0x13438,
      0x13470, 0x1347e, 0x134e0, 0x134fc, 0x135c0, 0x135f8, 0x13608,
      0x13610, 0x1361e, 0x13620, 0x1363c, 0x13640, 0x13678, 0x136f0,
      0x1370c, 0x13718, 0x13730, 0x1373e, 0x13760, 0x1377c, 0x1379c,
      0x137b8, 0x137c2, 0x137c4, 0x137c8, 0x137d0, 0x137de, 0x137e6,
      0x137ec, 0x13816, 0x13826, 0x1382c, 0x13846, 0x1384c, 0x13858,
      0x1386e, 0x13874, 0x13886, 0x13898, 0x138b0, 0x138be, 0x138ce,
      0x138dc, 0x138e2, 0x138e4, 0x138e8, 0x13906, 0x1390c, 0x13930,
      0x1393e, 0x13960, 0x1397c, 0x1398e, 0x1399c, 0x139b8, 0x139c8,
      0x139d0, 0x139de, 0x139e6, 0x139ec, 0x139fa, 0x13a06, 0x13a0c,
      0x13a18, 0x13a30, 0x13a3e, 0x13a60, 0x13a7c, 0x13ac0, 0x13af8,
      0x13b0e, 0x13b1c, 0x13b38, 0x13b70, 0x13b7e, 0x13b88, 0x13b90,
      0x13b9e, 0x13ba0, 0x13bbc, 0x13bcc, 0x13bd8, 0x13bee, 0x13bf2,
      0x13bf4, 0x13c12, 0x13c14, 0x13c22, 0x13c24, 0x13c28, 0x13c36,
      0x13c42, 0x13c48, 0x13c50, 0x13c5e, 0x13c66, 0x13c6c, 0x13c82,
      0x13c84, 0x13c90, 0x13c9e, 0x13ca0, 0x13cbc, 0x13cc6, 0x13ccc,
      0x13cd8, 0x13cee, 0x13d02, 0x13d04, 0x13d08, 0x13d10, 0x13d1e,
      0x13d20, 0x13d3c, 0x13d40, 0x13d78, 0x13d86, 0x13d8c, 0x13d98,
      0x13db0, 0x13dbe, 0x13dce, 0x13ddc, 0x13de4, 0x13de8, 0x13df6,
      0x13e1a, 0x13e2e, 0x13e32, 0x13e34, 0x13e4e, 0x13e5c, 0x13e62,
      0x13e64, 0x13e68, 0x13e76, 0x13e8e, 0x13e9c, 0x13eb8, 0x13ec2,
      0x13ec4, 0x13ec8, 0x13ed0, 0x13ede, 0x13ee6, 0x13eec, 0x13f26,
      0x13f2c, 0x13f3a, 0x13f46, 0x13f4c, 0x13f58, 0x13f6e, 0x13f72,
      0x13f74, 0x14082, 0x1409e, 0x140a0, 0x140bc, 0x14104, 0x14108,
      0x14110, 0x1411e, 0x14120, 0x1413c, 0x14140, 0x14178, 0x1418c,
      0x14198, 0x141b0, 0x141be, 0x141e2, 0x141e4, 0x141e8, 0x14208,
      0x14210, 0x1421e, 0x14220, 0x1423c, 0x14240, 0x14278, 0x142f0,
      0x14306, 0x1430c, 0x14318, 0x14330, 0x1433e, 0x14360, 0x1437c,
      0x1438e, 0x143c2, 0x143c4, 0x143c8, 0x143d0, 0x143e6, 0x143ec,
      0x14408, 0x14410, 0x1441e, 0x14420, 0x1443c, 0x14440, 0x14478,
      0x144f0, 0x145e0, 0x1460c, 0x14618, 0x14630, 0x1463e, 0x14660,
      0x1467c, 0x146c0, 0x146f8, 0x1471c, 0x14738, 0x14770, 0x1477e,
      0x14782, 0x14784, 0x14788, 0x14790, 0x147a0, 0x147bc, 0x147c6,
      0x147cc, 0x147d8, 0x147ee, 0x14810, 0x14820, 0x1483c, 0x14840,
      0x14878, 0x148f0, 0x149e0, 0x14bc0, 0x14c30, 0x14c3e, 0x14c60,
      0x14c7c, 0x14cc0, 0x14cf8, 0x14df0, 0x14e38, 0x14e70, 0x14e7e,
      0x14ee0, 0x14efc, 0x14f04, 0x14f08, 0x14f10, 0x14f1e, 0x14f20,
      0x14f3c, 0x14f40, 0x14f78, 0x14f86, 0x14f8c, 0x14f98, 0x14fb0,
      0x14fce, 0x14fdc, 0x15020, 0x15040, 0x15078, 0x150f0, 0x151e0,
      0x153c0, 0x15860, 0x1587c, 0x158c0, 0x158f8, 0x159f0, 0x15be0,
      0x15c70, 0x15c7e, 0x15ce0, 0x15cfc, 0x15dc0, 0x15df8, 0x15e08,
      0x15e10, 0x15e20, 0x15e40, 0x15e78, 0x15ef0, 0x15f0c, 0x15f18,
      0x15f30, 0x15f60, 0x15f7c, 0x15f8e, 0x15f9c, 0x15fb8, 0x1604e,
      0x1605c, 0x1608e, 0x1609c, 0x160b8, 0x160c2, 0x160c4, 0x160c8,
      0x160de, 0x1610e, 0x1611c, 0x16138, 0x16170, 0x1617e, 0x16184,
      0x16188, 0x16190, 0x1619e, 0x161a0, 0x161bc, 0x161c6, 0x161cc,
      0x161d8, 0x161f2, 0x161f4, 0x1620e, 0x1621c, 0x16238, 0x16270,
      0x1627e, 0x162e0, 0x162fc, 0x16304, 0x16308, 0x16310, 0x1631e,
      0x16320, 0x1633c, 0x16340, 0x16378, 0x16386, 0x1638c, 0x16398,
      0x163b0, 0x163be, 0x163ce, 0x163dc, 0x163e2, 0x163e4, 0x163e8,
      0x163f6, 0x1640e, 0x1641c, 0x16438, 0x16470, 0x1647e, 0x164e0,
      0x164fc, 0x165c0, 0x165f8, 0x16610, 0x1661e, 0x16620, 0x1663c,
      0x16640, 0x16678, 0x166f0, 0x16718, 0x16730, 0x1673e, 0x16760,
      0x1677c, 0x1678e, 0x1679c, 0x167b8, 0x167c2, 0x167c4, 0x167c8,
      0x167d0, 0x167de, 0x167e6, 0x167ec, 0x1681c, 0x16838, 0x16870,
      0x168e0, 0x168fc, 0x169c0, 0x169f8, 0x16bf0, 0x16c10, 0x16c1e,
      0x16c20, 0x16c3c, 0x16c40, 0x16c78, 0x16cf0, 0x16de0, 0x16e18,
      0x16e30, 0x16e3e, 0x16e60, 0x16e7c, 0x16ec0, 0x16ef8, 0x16f1c,
      0x16f38, 0x16f70, 0x16f7e, 0x16f84, 0x16f88, 0x16f90, 0x16f9e,
      0x16fa0, 0x16fbc, 0x16fc6, 0x16fcc, 0x16fd8, 0x17026, 0x1702c,
      0x17046, 0x1704c, 0x17058, 0x1706e, 0x17086, 0x1708c, 0x17098,
      0x170b0, 0x170be, 0x170ce, 0x170dc, 0x170e8, 0x17106, 0x1710c,
      0x17118, 0x17130, 0x1713e, 0x17160, 0x1717c, 0x1718e, 0x1719c,
      0x171b8, 0x171c2, 0x171c4, 0x171c8, 0x171d0, 0x171de, 0x171e6,
      0x171ec, 0x171fa, 0x17206, 0x1720c, 0x17218, 0x17230, 0x1723e,
      0x17260, 0x1727c, 0x172c0, 0x172f8, 0x1730e, 0x1731c, 0x17338,
      0x17370, 0x1737e, 0x17388, 0x17390, 0x1739e, 0x173a0, 0x173bc,
      0x173cc, 0x173d8, 0x173ee, 0x173f2, 0x173f4, 0x1740c, 0x17418,
      0x17430, 0x1743e, 0x17460, 0x1747c, 0x174c0, 0x174f8, 0x175f0,
      0x1760e, 0x1761c, 0x17638, 0x17670, 0x1767e, 0x176e0, 0x176fc,
      0x17708, 0x17710, 0x1771e, 0x17720, 0x1773c, 0x17740, 0x17778,
      0x17798, 0x177b0, 0x177be, 0x177dc, 0x177e2, 0x177e4, 0x177e8,
      0x17822, 0x17824, 0x17828, 0x17836, 0x17842, 0x17844, 0x17848,
      0x17850, 0x1785e, 0x17866, 0x1786c, 0x17882, 0x17884, 0x17888,
      0x17890, 0x1789e, 0x178a0, 0x178bc, 0x178c6, 0x178cc, 0x178d8,
      0x178ee, 0x178f2, 0x178f4, 0x17902, 0x17904, 0x17908, 0x17910,
      0x1791e, 0x17920, 0x1793c, 0x17940, 0x17978, 0x17986, 0x1798c,
      0x17998, 0x179b0, 0x179be, 0x179ce, 0x179dc, 0x179e2, 0x179e4,
      0x179e8, 0x179f6, 0x17a04, 0x17a08, 0x17a10, 0x17a1e, 0x17a20,
      0x17a3c, 0x17a40, 0x17a78, 0x17af0, 0x17b06, 0x17b0c, 0x17b18,
      0x17b30, 0x17b3e, 0x17b60, 0x17b7c, 0x17b8e, 0x17b9c, 0x17bb8,
      0x17bc4, 0x17bc8, 0x17bd0, 0x17bde, 0x17be6, 0x17bec, 0x17c2e,
      0x17c32, 0x17c34, 0x17c4e, 0x17c5c, 0x17c62, 0x17c64, 0x17c68,
      0x17c76, 0x17c8e, 0x17c9c, 0x17cb8, 0x17cc2, 0x17cc4, 0x17cc8,
      0x17cd0, 0x17cde, 0x17ce6, 0x17cec, 0x17d0e, 0x17d1c, 0x17d38,
      0x17d70, 0x17d82, 0x17d84, 0x17d88, 0x17d90, 0x17d9e, 0x17da0,
      0x17dbc, 0x17dc6, 0x17dcc, 0x17dd8, 0x17dee, 0x17e26, 0x17e2c,
      0x17e3a, 0x17e46, 0x17e4c, 0x17e58, 0x17e6e, 0x17e72, 0x17e74,
      0x17e86, 0x17e8c, 0x17e98, 0x17eb0, 0x17ece, 0x17edc, 0x17ee2,
      0x17ee4, 0x17ee8, 0x17ef6, 0x1813a, 0x18172, 0x18174, 0x18216,
      0x18226, 0x1823a, 0x1824c, 0x18258, 0x1826e, 0x18272, 0x18274,
      0x18298, 0x182be, 0x182e2, 0x182e4, 0x182e8, 0x182f6, 0x1835e,
      0x1837a, 0x183ae, 0x183d6, 0x18416, 0x18426, 0x1842c, 0x1843a,
      0x18446, 0x18458, 0x1846e, 0x18472, 0x18474, 0x18486, 0x184b0,
      0x184be, 0x184ce, 0x184dc, 0x184e2, 0x184e4, 0x184e8, 0x184f6,
      0x18506, 0x1850c, 0x18518, 0x18530, 0x1853e, 0x18560, 0x1857c,
      0x1858e, 0x1859c, 0x185b8, 0x185c2, 0x185c4, 0x185c8, 0x185d0,
      0x185de, 0x185e6, 0x185ec, 0x185fa, 0x18612, 0x18614, 0x18622,
      0x18628, 0x18636, 0x18642, 0x18650, 0x1865e, 0x1867a, 0x18682,
      0x18684, 0x18688, 0x18690, 0x1869e, 0x186a0, 0x186bc, 0x186c6,
      0x186cc, 0x186d8, 0x186ee, 0x186f2, 0x186f4, 0x1872e, 0x1874e,
      0x1875c, 0x18796, 0x187a6, 0x187ac, 0x187d2, 0x187d4, 0x18826,
      0x1882c, 0x1883a, 0x18846, 0x1884c, 0x18858, 0x1886e, 0x18872,
      0x18874, 0x18886, 0x18898, 0x188b0, 0x188be, 0x188ce, 0x188dc,
      0x188e2, 0x188e4, 0x188e8, 0x188f6, 0x1890c, 0x18930, 0x1893e,
      0x18960, 0x1897c, 0x1898e, 0x189b8, 0x189c2, 0x189c8, 0x189d0,
      0x189de, 0x189e6, 0x189ec, 0x189fa, 0x18a18, 0x18a30, 0x18a3e,
      0x18a60, 0x18a7c, 0x18ac0, 0x18af8, 0x18b1c, 0x18b38, 0x18b70,
      0x18b7e, 0x18b82, 0x18b84, 0x18b88, 0x18b90, 0x18b9e, 0x18ba0,
      0x18bbc, 0x18bc6, 0x18bcc, 0x18bd8, 0x18bee, 0x18bf2, 0x18bf4,
      0x18c22, 0x18c24, 0x18c28, 0x18c36, 0x18c42, 0x18c48, 0x18c50,
      0x18c5e, 0x18c66, 0x18c7a, 0x18c82, 0x18c84, 0x18c90, 0x18c9e,
      0x18ca0, 0x18cbc, 0x18ccc, 0x18cf2, 0x18cf4, 0x18d04, 0x18d08,
      0x18d10, 0x18d1e, 0x18d20, 0x18d3c, 0x18d40, 0x18d78, 0x18d86,
      0x18d98, 0x18dce, 0x18de2, 0x18de4, 0x18de8, 0x18e2e, 0x18e32,
      0x18e34, 0x18e4e, 0x18e5c, 0x18e62, 0x18e64, 0x18e68, 0x18e8e,
      0x18e9c, 0x18eb8, 0x18ec2, 0x18ec4, 0x18ec8, 0x18ed0, 0x18efa,
      0x18f16, 0x18f26, 0x18f2c, 0x18f46, 0x18f4c, 0x18f58, 0x18f6e,
      0x18f8a, 0x18f92, 0x18f94, 0x18fa2, 0x18fa4, 0x18fa8, 0x18fb6,
      0x1902c, 0x1903a, 0x19046, 0x1904c, 0x19058, 0x19072, 0x19074,
      0x19086, 0x19098, 0x190b0, 0x190be, 0x190ce, 0x190dc, 0x190e2,
      0x190e8, 0x190f6, 0x19106, 0x1910c, 0x19130, 0x1913e, 0x19160,
      0x1917c, 0x1918e, 0x1919c, 0x191b8, 0x191c2, 0x191c8, 0x191d0,
      0x191de, 0x191e6, 0x191ec, 0x191fa, 0x19218, 0x1923e, 0x19260,
      0x1927c, 0x192c0, 0x192f8, 0x19338, 0x19370, 0x1937e, 0x19382,
      0x19384, 0x19390, 0x1939e, 0x193a0, 0x193bc, 0x193c6, 0x193cc,
      0x193d8, 0x193ee, 0x193f2, 0x193f4, 0x19430, 0x1943e, 0x19460,
      0x1947c, 0x194c0, 0x194f8, 0x195f0, 0x19638, 0x19670, 0x1967e,
      0x196e0, 0x196fc, 0x19702, 0x19704, 0x19708, 0x19710, 0x19720,
      0x1973c, 0x19740, 0x19778, 0x19786, 0x1978c, 0x19798, 0x197b0,
      0x197be, 0x197ce, 0x197dc, 0x197e2, 0x197e4, 0x197e8, 0x19822,
      0x19824, 0x19842, 0x19848, 0x19850, 0x1985e, 0x19866, 0x1987a,
      0x19882, 0x19884, 0x19890, 0x1989e, 0x198a0, 0x198bc, 0x198cc,
      0x198f2, 0x198f4, 0x19902, 0x19908, 0x1991e, 0x19920, 0x1993c,
      0x19940, 0x19978, 0x19986, 0x19998, 0x199ce, 0x199e2, 0x199e4,
      0x199e8, 0x19a08, 0x19a10, 0x19a1e, 0x19a20, 0x19a3c, 0x19a40,
      0x19a78, 0x19af0, 0x19b18, 0x19b3e, 0x19b60, 0x19b9c, 0x19bc2,
      0x19bc4, 0x19bc8, 0x19bd0, 0x19be6, 0x19c2e, 0x19c34, 0x19c4e,
      0x19c5c, 0x19c62, 0x19c64, 0x19c68, 0x19c8e, 0x19c9c, 0x19cb8,
      0x19cc2, 0x19cc8, 0x19cd0, 0x19ce6, 0x19cfa, 0x19d0e, 0x19d1c,
      0x19d38, 0x19d70, 0x19d7e, 0x19d82, 0x19d84, 0x19d88, 0x19d90,
      0x19da0, 0x19dcc, 0x19df2, 0x19df4, 0x19e16, 0x19e26, 0x19e2c,
      0x19e46, 0x19e4c, 0x19e58, 0x19e74, 0x19e86, 0x19e8c, 0x19e98,
      0x19eb0, 0x19ebe, 0x19ece, 0x19ee2, 0x19ee4, 0x19ee8, 0x19f0a,
      0x19f12, 0x19f14, 0x19f22, 0x19f24, 0x19f28, 0x19f42, 0x19f44,
      0x19f48, 0x19f50, 0x19f5e, 0x19f6c, 0x19f9a, 0x19fae, 0x19fb2,
      0x19fb4, 0x1a046, 0x1a04c, 0x1a072, 0x1a074, 0x1a086, 0x1a08c,
      0x1a098, 0x1a0b0, 0x1a0be, 0x1a0e2, 0x1a0e4, 0x1a0e8, 0x1a0f6,
      0x1a106, 0x1a10c, 0x1a118, 0x1a130, 0x1a13e, 0x1a160, 0x1a17c,
      0x1a18e, 0x1a19c, 0x1a1b8, 0x1a1c2, 0x1a1c4, 0x1a1c8, 0x1a1d0,
      0x1a1de, 0x1a1e6, 0x1a1ec, 0x1a218, 0x1a230, 0x1a23e, 0x1a260,
      0x1a27c, 0x1a2c0, 0x1a2f8, 0x1a31c, 0x1a338, 0x1a370, 0x1a37e,
      0x1a382, 0x1a384, 0x1a388, 0x1a390, 0x1a39e, 0x1a3a0, 0x1a3bc,
      0x1a3c6, 0x1a3cc, 0x1a3d8, 0x1a3ee, 0x1a3f2, 0x1a3f4, 0x1a418,
      0x1a430, 0x1a43e, 0x1a460, 0x1a47c, 0x1a4c0, 0x1a4f8, 0x1a5f0,
      0x1a61c, 0x1a638, 0x1a670, 0x1a67e, 0x1a6e0, 0x1a6fc, 0x1a702,
      0x1a704, 0x1a708, 0x1a710, 0x1a71e, 0x1a720, 0x1a73c, 0x1a740,
      0x1a778, 0x1a786, 0x1a78c, 0x1a798, 0x1a7b0, 0x1a7be, 0x1a7ce,
      0x1a7dc, 0x1a7e2, 0x1a7e4, 0x1a7e8, 0x1a830, 0x1a860, 0x1a87c,
      0x1a8c0, 0x1a8f8, 0x1a9f0, 0x1abe0, 0x1ac70, 0x1ac7e, 0x1ace0,
      0x1acfc, 0x1adc0, 0x1adf8, 0x1ae04, 0x1ae08, 0x1ae10, 0x1ae20,
      0x1ae3c, 0x1ae40, 0x1ae78, 0x1aef0, 0x1af06, 0x1af0c, 0x1af18,
      0x1af30, 0x1af3e, 0x1af60, 0x1af7c, 0x1af8e, 0x1af9c, 0x1afb8,
      0x1afc4, 0x1afc8, 0x1afd0, 0x1afde, 0x1b042, 0x1b05e, 0x1b07a,
      0x1b082, 0x1b084, 0x1b088, 0x1b090, 0x1b09e, 0x1b0a0, 0x1b0bc,
      0x1b0cc, 0x1b0f2, 0x1b0f4, 0x1b102, 0x1b104, 0x1b108, 0x1b110,
      0x1b11e, 0x1b120, 0x1b13c, 0x1b140, 0x1b178, 0x1b186, 0x1b198,
      0x1b1ce, 0x1b1e2, 0x1b1e4, 0x1b1e8, 0x1b204, 0x1b208, 0x1b210,
      0x1b21e, 0x1b220, 0x1b23c, 0x1b240, 0x1b278, 0x1b2f0, 0x1b30c,
      0x1b33e, 0x1b360, 0x1b39c, 0x1b3c2, 0x1b3c4, 0x1b3c8, 0x1b3d0,
      0x1b3e6, 0x1b410, 0x1b41e, 0x1b420, 0x1b43c, 0x1b440, 0x1b478,
      0x1b4f0, 0x1b5e0, 0x1b618, 0x1b660, 0x1b67c, 0x1b6c0, 0x1b738,
      0x1b782, 0x1b784, 0x1b788, 0x1b790, 0x1b79e, 0x1b7a0, 0x1b7cc,
      0x1b82e, 0x1b84e, 0x1b85c, 0x1b88e, 0x1b89c, 0x1b8b8, 0x1b8c2,
      0x1b8c4, 0x1b8c8, 0x1b8d0, 0x1b8e6, 0x1b8fa, 0x1b90e, 0x1b91c,
      0x1b938, 0x1b970, 0x1b97e, 0x1b982, 0x1b984, 0x1b988, 0x1b990,
      0x1b99e, 0x1b9a0, 0x1b9cc, 0x1b9f2, 0x1b9f4, 0x1ba0e, 0x1ba1c,
      0x1ba38, 0x1ba70, 0x1ba7e, 0x1bae0, 0x1bafc, 0x1bb08, 0x1bb10,
      0x1bb20, 0x1bb3c, 0x1bb40, 0x1bb98, 0x1bbce, 0x1bbe2, 0x1bbe4,
      0x1bbe8, 0x1bc16, 0x1bc26, 0x1bc2c, 0x1bc46, 0x1bc4c, 0x1bc58,
      0x1bc72, 0x1bc74, 0x1bc86, 0x1bc8c, 0x1bc98, 0x1bcb0, 0x1bcbe,
      0x1bcce, 0x1bce2, 0x1bce4, 0x1bce8, 0x1bd06, 0x1bd0c, 0x1bd18,
      0x1bd30, 0x1bd3e, 0x1bd60, 0x1bd7c, 0x1bd9c, 0x1bdc2, 0x1bdc4,
      0x1bdc8, 0x1bdd0, 0x1bde6, 0x1bdfa, 0x1be12, 0x1be14, 0x1be22,
      0x1be24, 0x1be28, 0x1be42, 0x1be44, 0x1be48, 0x1be50, 0x1be5e,
      0x1be66, 0x1be82, 0x1be84, 0x1be88, 0x1be90, 0x1be9e, 0x1bea0,
      0x1bebc, 0x1becc, 0x1bef4, 0x1bf1a, 0x1bf2e, 0x1bf32, 0x1bf34,
      0x1bf4e, 0x1bf5c, 0x1bf62, 0x1bf64, 0x1bf68, 0x1c09a, 0x1c0b2,
      0x1c0b4, 0x1c11a, 0x1c132, 0x1c134, 0x1c162, 0x1c164, 0x1c168,
      0x1c176, 0x1c1ba, 0x1c21a, 0x1c232, 0x1c234, 0x1c24e, 0x1c25c,
      0x1c262, 0x1c264, 0x1c268, 0x1c276, 0x1c28e, 0x1c2c2, 0x1c2c4,
      0x1c2c8, 0x1c2d0, 0x1c2de, 0x1c2e6, 0x1c2ec, 0x1c2fa, 0x1c316,
      0x1c326, 0x1c33a, 0x1c346, 0x1c34c, 0x1c372, 0x1c374, 0x1c41a,
      0x1c42e, 0x1c432, 0x1c434, 0x1c44e, 0x1c45c, 0x1c462, 0x1c464,
      0x1c468, 0x1c476, 0x1c48e, 0x1c49c, 0x1c4b8, 0x1c4c2, 0x1c4c8,
      0x1c4d0, 0x1c4de, 0x1c4e6, 0x1c4ec, 0x1c4fa, 0x1c51c, 0x1c538,
      0x1c570, 0x1c57e, 0x1c582, 0x1c584, 0x1c588, 0x1c590, 0x1c59e,
      0x1c5a0, 0x1c5bc, 0x1c5c6, 0x1c5cc, 0x1c5d8, 0x1c5ee, 0x1c5f2,
      0x1c5f4, 0x1c616, 0x1c626, 0x1c62c, 0x1c63a, 0x1c646, 0x1c64c,
      0x1c658, 0x1c66e, 0x1c672, 0x1c674, 0x1c686, 0x1c68c, 0x1c698,
      0x1c6b0, 0x1c6be, 0x1c6ce, 0x1c6dc, 0x1c6e2, 0x1c6e4, 0x1c6e8,
      0x1c712, 0x1c714, 0x1c722, 0x1c728, 0x1c736, 0x1c742, 0x1c744,
      0x1c748, 0x1c750, 0x1c75e, 0x1c766, 0x1c76c, 0x1c77a, 0x1c7ae,
      0x1c7d6, 0x1c7ea, 0x1c81a, 0x1c82e, 0x1c832, 0x1c834, 0x1c84e,
      0x1c85c, 0x1c862, 0x1c864, 0x1c868, 0x1c876, 0x1c88e, 0x1c89c,
      0x1c8b8, 0x1c8c2, 0x1c8c8, 0x1c8d0, 0x1c8de, 0x1c8e6, 0x1c8ec,
      0x1c8fa, 0x1c90e, 0x1c938, 0x1c970, 0x1c97e, 0x1c982, 0x1c984,
      0x1c990, 0x1c99e, 0x1c9a0, 0x1c9bc, 0x1c9c6, 0x1c9cc, 0x1c9d8,
      0x1c9ee, 0x1c9f2, 0x1c9f4, 0x1ca38, 0x1ca70, 0x1ca7e, 0x1cae0,
      0x1cafc, 0x1cb02, 0x1cb04, 0x1cb08, 0x1cb10, 0x1cb20, 0x1cb3c,
      0x1cb40, 0x1cb78, 0x1cb86, 0x1cb8c, 0x1cb98, 0x1cbb0, 0x1cbbe,
      0x1cbce, 0x1cbdc, 0x1cbe2, 0x1cbe4, 0x1cbe8, 0x1cbf6, 0x1cc16,
      0x1cc26, 0x1cc2c, 0x1cc3a, 0x1cc46, 0x1cc58, 0x1cc72, 0x1cc74,
      0x1cc86, 0x1ccb0, 0x1ccbe, 0x1ccce, 0x1cce2, 0x1cce4, 0x1cce8,
      0x1cd06, 0x1cd0c, 0x1cd18, 0x1cd30, 0x1cd3e, 0x1cd60, 0x1cd7c,
      0x1cd9c, 0x1cdc2, 0x1cdc4, 0x1cdc8, 0x1cdd0, 0x1cdde, 0x1cde6,
      0x1cdfa, 0x1ce22, 0x1ce28, 0x1ce42, 0x1ce50, 0x1ce5e, 0x1ce66,
      0x1ce7a, 0x1ce82, 0x1ce84, 0x1ce88, 0x1ce90, 0x1ce9e, 0x1cea0,
      0x1cebc, 0x1cecc, 0x1cef2, 0x1cef4, 0x1cf2e, 0x1cf32, 0x1cf34,
      0x1cf4e, 0x1cf5c, 0x1cf62, 0x1cf64, 0x1cf68, 0x1cf96, 0x1cfa6,
      0x1cfac, 0x1cfca, 0x1cfd2, 0x1cfd4, 0x1d02e, 0x1d032, 0x1d034,
      0x1d04e, 0x1d05c, 0x1d062, 0x1d064, 0x1d068, 0x1d076, 0x1d08e,
      0x1d09c, 0x1d0b8, 0x1d0c2, 0x1d0c4, 0x1d0c8, 0x1d0d0, 0x1d0de,
      0x1d0e6, 0x1d0ec, 0x1d0fa, 0x1d11c, 0x1d138, 0x1d170, 0x1d17e,
      0x1d182, 0x1d184, 0x1d188, 0x1d190, 0x1d19e, 0x1d1a0, 0x1d1bc,
      0x1d1c6, 0x1d1cc, 0x1d1d8, 0x1d1ee, 0x1d1f2, 0x1d1f4, 0x1d21c,
      0x1d238, 0x1d270, 0x1d27e, 0x1d2e0, 0x1d2fc, 0x1d302, 0x1d304,
      0x1d308, 0x1d310, 0x1d31e, 0x1d320, 0x1d33c, 0x1d340, 0x1d378,
      0x1d386, 0x1d38c, 0x1d398, 0x1d3b0, 0x1d3be, 0x1d3ce, 0x1d3dc,
      0x1d3e2, 0x1d3e4, 0x1d3e8, 0x1d3f6, 0x1d470, 0x1d47e, 0x1d4e0,
      0x1d4fc, 0x1d5c0, 0x1d5f8, 0x1d604, 0x1d608, 0x1d610, 0x1d620,
      0x1d640, 0x1d678, 0x1d6f0, 0x1d706, 0x1d70c, 0x1d718, 0x1d730,
      0x1d73e, 0x1d760, 0x1d77c, 0x1d78e, 0x1d79c, 0x1d7b8, 0x1d7c2,
      0x1d7c4, 0x1d7c8, 0x1d7d0, 0x1d7de, 0x1d7e6, 0x1d7ec, 0x1d826,
      0x1d82c, 0x1d83a, 0x1d846, 0x1d84c, 0x1d858, 0x1d872, 0x1d874,
      0x1d886, 0x1d88c, 0x1d898, 0x1d8b0, 0x1d8be, 0x1d8ce, 0x1d8e2,
      0x1d8e4, 0x1d8e8, 0x1d8f6, 0x1d90c, 0x1d918, 0x1d930, 0x1d93e,
      0x1d960, 0x1d97c, 0x1d99c, 0x1d9c2, 0x1d9c4, 0x1d9c8, 0x1d9d0,
      0x1d9e6, 0x1d9fa, 0x1da0c, 0x1da18, 0x1da30, 0x1da3e, 0x1da60,
      0x1da7c, 0x1dac0, 0x1daf8, 0x1db38, 0x1db82, 0x1db84, 0x1db88,
      0x1db90, 0x1db9e, 0x1dba0, 0x1dbcc, 0x1dbf2, 0x1dbf4, 0x1dc22,
      0x1dc42, 0x1dc44, 0x1dc48, 0x1dc50, 0x1dc5e, 0x1dc66, 0x1dc7a,
      0x1dc82, 0x1dc84, 0x1dc88, 0x1dc90, 0x1dc9e, 0x1dca0, 0x1dcbc,
      0x1dccc, 0x1dcf2, 0x1dcf4, 0x1dd04, 0x1dd08, 0x1dd10, 0x1dd1e,
      0x1dd20, 0x1dd3c, 0x1dd40, 0x1dd78, 0x1dd86, 0x1dd98, 0x1ddce,
      0x1dde2, 0x1dde4, 0x1dde8, 0x1de2e, 0x1de32, 0x1de34, 0x1de4e,
      0x1de5c, 0x1de62, 0x1de64, 0x1de68, 0x1de8e, 0x1de9c, 0x1deb8,
      0x1dec2, 0x1dec4, 0x1dec8, 0x1ded0, 0x1dee6, 0x1defa, 0x1df16,
      0x1df26, 0x1df2c, 0x1df46, 0x1df4c, 0x1df58, 0x1df72, 0x1df74,
      0x1df8a, 0x1df92, 0x1df94, 0x1dfa2, 0x1dfa4, 0x1dfa8, 0x1e08a,
      0x1e092, 0x1e094, 0x1e0a2, 0x1e0a4, 0x1e0a8, 0x1e0b6, 0x1e0da,
      0x1e10a, 0x1e112, 0x1e114, 0x1e122, 0x1e124, 0x1e128, 0x1e136,
      0x1e142, 0x1e144, 0x1e148, 0x1e150, 0x1e166, 0x1e16c, 0x1e17a,
      0x1e19a, 0x1e1b2, 0x1e1b4, 0x1e20a, 0x1e212, 0x1e214, 0x1e222,
      0x1e224, 0x1e228, 0x1e236, 0x1e242, 0x1e248, 0x1e250, 0x1e25e,
      0x1e266, 0x1e26c, 0x1e27a, 0x1e282, 0x1e284, 0x1e288, 0x1e290,
      0x1e2a0, 0x1e2bc, 0x1e2c6, 0x1e2cc, 0x1e2d8, 0x1e2ee, 0x1e2f2,
      0x1e2f4, 0x1e31a, 0x1e332, 0x1e334, 0x1e35c, 0x1e362, 0x1e364,
      0x1e368, 0x1e3ba, 0x1e40a, 0x1e412, 0x1e414, 0x1e422, 0x1e428,
      0x1e436, 0x1e442, 0x1e448, 0x1e450, 0x1e45e, 0x1e466, 0x1e46c,
      0x1e47a, 0x1e482, 0x1e484, 0x1e490, 0x1e49e, 0x1e4a0, 0x1e4bc,
      0x1e4c6, 0x1e4cc, 0x1e4d8, 0x1e4ee, 0x1e4f2, 0x1e4f4, 0x1e502,
      0x1e504, 0x1e508, 0x1e510, 0x1e51e, 0x1e520, 0x1e53c, 0x1e540,
      0x1e578, 0x1e586, 0x1e58c, 0x1e598, 0x1e5b0, 0x1e5be, 0x1e5ce,
      0x1e5dc, 0x1e5e2, 0x1e5e4, 0x1e5e8, 0x1e5f6, 0x1e61a, 0x1e62e,
      0x1e632, 0x1e634, 0x1e64e, 0x1e65c, 0x1e662, 0x1e668, 0x1e68e,
      0x1e69c, 0x1e6b8, 0x1e6c2, 0x1e6c4, 0x1e6c8, 0x1e6d0, 0x1e6e6,
      0x1e6fa, 0x1e716, 0x1e726, 0x1e72c, 0x1e73a, 0x1e746, 0x1e74c,
      0x1e758, 0x1e772, 0x1e774, 0x1e792, 0x1e794, 0x1e7a2, 0x1e7a4,
      0x1e7a8, 0x1e7b6, 0x1e812, 0x1e814, 0x1e822, 0x1e824, 0x1e828,
      0x1e836, 0x1e842, 0x1e844, 0x1e848, 0x1e850, 0x1e85e, 0x1e866,
      0x1e86c, 0x1e87a, 0x1e882, 0x1e884, 0x1e888, 0x1e890, 0x1e89e,
      0x1e8a0, 0x1e8bc, 0x1e8c6, 0x1e8cc, 0x1e8d8, 0x1e8ee, 0x1e8f2,
      0x1e8f4, 0x1e902, 0x1e904, 0x1e908, 0x1e910, 0x1e920, 0x1e93c,
      0x1e940, 0x1e978, 0x1e986, 0x1e98c, 0x1e998, 0x1e9b0, 0x1e9be,
      0x1e9ce, 0x1e9dc, 0x1e9e2, 0x1e9e4, 0x1e9e8, 0x1e9f6, 0x1ea04,
      0x1ea08, 0x1ea10, 0x1ea20, 0x1ea40, 0x1ea78, 0x1eaf0, 0x1eb06,
      0x1eb0c, 0x1eb18, 0x1eb30, 0x1eb3e, 0x1eb60, 0x1eb7c, 0x1eb8e,
      0x1eb9c, 0x1ebb8, 0x1ebc2, 0x1ebc4, 0x1ebc8, 0x1ebd0, 0x1ebde,
      0x1ebe6, 0x1ebec, 0x1ec1a, 0x1ec2e, 0x1ec32, 0x1ec34, 0x1ec4e,
      0x1ec5c, 0x1ec62, 0x1ec64, 0x1ec68, 0x1ec8e, 0x1ec9c, 0x1ecb8,
      0x1ecc2, 0x1ecc4, 0x1ecc8, 0x1ecd0, 0x1ece6, 0x1ecfa, 0x1ed0e,
      0x1ed1c, 0x1ed38, 0x1ed70, 0x1ed7e, 0x1ed82, 0x1ed84, 0x1ed88,
      0x1ed90, 0x1ed9e, 0x1eda0, 0x1edcc, 0x1edf2, 0x1edf4, 0x1ee16,
      0x1ee26, 0x1ee2c, 0x1ee3a, 0x1ee46, 0x1ee4c, 0x1ee58, 0x1ee6e,
      0x1ee72, 0x1ee74, 0x1ee86, 0x1ee8c, 0x1ee98, 0x1eeb0, 0x1eebe,
      0x1eece, 0x1eedc, 0x1eee2, 0x1eee4, 0x1eee8, 0x1ef12, 0x1ef22,
      0x1ef24, 0x1ef28, 0x1ef36, 0x1ef42, 0x1ef44, 0x1ef48, 0x1ef50,
      0x1ef5e, 0x1ef66, 0x1ef6c, 0x1ef7a, 0x1efae, 0x1efb2, 0x1efb4,
      0x1efd6, 0x1f096, 0x1f0a6, 0x1f0ac, 0x1f0ba, 0x1f0ca, 0x1f0d2,
      0x1f0d4, 0x1f116, 0x1f126, 0x1f12c, 0x1f13a, 0x1f146, 0x1f14c,
      0x1f158, 0x1f16e, 0x1f172, 0x1f174, 0x1f18a, 0x1f192, 0x1f194,
      0x1f1a2, 0x1f1a4, 0x1f1a8, 0x1f1da, 0x1f216, 0x1f226, 0x1f22c,
      0x1f23a, 0x1f246, 0x1f258, 0x1f26e, 0x1f272, 0x1f274, 0x1f286,
      0x1f28c, 0x1f298, 0x1f2b0, 0x1f2be, 0x1f2ce, 0x1f2dc, 0x1f2e2,
      0x1f2e4, 0x1f2e8, 0x1f2f6, 0x1f30a, 0x1f312, 0x1f314, 0x1f322,
      0x1f328, 0x1f342, 0x1f344, 0x1f348, 0x1f350, 0x1f35e, 0x1f366,
      0x1f37a, 0x1f39a, 0x1f3ae, 0x1f3b2, 0x1f3b4, 0x1f416, 0x1f426,
      0x1f42c, 0x1f43a, 0x1f446, 0x1f44c, 0x1f458, 0x1f46e, 0x1f472,
      0x1f474, 0x1f486, 0x1f48c, 0x1f498, 0x1f4b0, 0x1f4be, 0x1f4ce,
      0x1f4dc, 0x1f4e2, 0x1f4e4, 0x1f4e8, 0x1f4f6, 0x1f506, 0x1f50c,
      0x1f518, 0x1f530, 0x1f53e, 0x1f560, 0x1f57c, 0x1f58e, 0x1f59c,
      0x1f5b8, 0x1f5c2, 0x1f5c4, 0x1f5c8, 0x1f5d0, 0x1f5de, 0x1f5e6,
      0x1f5ec, 0x1f5fa, 0x1f60a, 0x1f612, 0x1f614, 0x1f622, 0x1f624,
      0x1f628, 0x1f636, 0x1f642, 0x1f644, 0x1f648, 0x1f650, 0x1f65e,
      0x1f666, 0x1f67a, 0x1f682, 0x1f684, 0x1f688, 0x1f690, 0x1f69e,
      0x1f6a0, 0x1f6bc, 0x1f6cc, 0x1f6f2, 0x1f6f4, 0x1f71a, 0x1f72e,
      0x1f732, 0x1f734, 0x1f74e, 0x1f75c, 0x1f762, 0x1f764, 0x1f768,
      0x1f776, 0x1f796, 0x1f7a6, 0x1f7ac, 0x1f7ba, 0x1f7d2, 0x1f7d4,
      0x1f89a, 0x1f8ae, 0x1f8b2, 0x1f8b4, 0x1f8d6, 0x1f8ea, 0x1f91a,
      0x1f92e, 0x1f932, 0x1f934, 0x1f94e, 0x1f95c, 0x1f962, 0x1f964,
      0x1f968, 0x1f976, 0x1f996, 0x1f9a6, 0x1f9ac, 0x1f9ba, 0x1f9ca,
      0x1f9d2, 0x1f9d4, 0x1fa1a, 0x1fa2e, 0x1fa32, 0x1fa34, 0x1fa4e,
      0x1fa5c, 0x1fa62, 0x1fa64, 0x1fa68, 0x1fa76, 0x1fa8e, 0x1fa9c,
      0x1fab8, 0x1fac2, 0x1fac4, 0x1fac8, 0x1fad0, 0x1fade, 0x1fae6,
      0x1faec, 0x1fb16, 0x1fb26, 0x1fb2c, 0x1fb3a, 0x1fb46, 0x1fb4c,
      0x1fb58, 0x1fb6e, 0x1fb72, 0x1fb74, 0x1fb8a, 0x1fb92, 0x1fb94,
      0x1fba2, 0x1fba4, 0x1fba8, 0x1fbb6, 0x1fbda};

  /**
   * This table contains to codewords for all symbols.
   */
  private static final int[] CODEWORD_TABLE = {2627, 1819, 2622, 2621, 1813,
      1812, 2729, 2724, 2723, 2779, 2774, 2773, 902, 896, 908, 868, 865,
      861, 859, 2511, 873, 871, 1780, 835, 2493, 825, 2491, 842, 837, 844,
      1764, 1762, 811, 810, 809, 2483, 807, 2482, 806, 2480, 815, 814, 813,
      812, 2484, 817, 816, 1745, 1744, 1742, 1746, 2655, 2637, 2635, 2626,
      2625, 2623, 2628, 1820, 2752, 2739, 2737, 2728, 2727, 2725, 2730,
      2785, 2783, 2778, 2777, 2775, 2780, 787, 781, 747, 739, 736, 2413,
      754, 752, 1719, 692, 689, 681, 2371, 678, 2369, 700, 697, 694, 703,
      1688, 1686, 642, 638, 2343, 631, 2341, 627, 2338, 651, 646, 643, 2345,
      654, 652, 1652, 1650, 1647, 1654, 601, 599, 2322, 596, 2321, 594,
      2319, 2317, 611, 610, 608, 606, 2324, 603, 2323, 615, 614, 612, 1617,
      1616, 1614, 1612, 616, 1619, 1618, 2575, 2538, 2536, 905, 901, 898,
      909, 2509, 2507, 2504, 870, 867, 864, 860, 2512, 875, 872, 1781, 2490,
      2489, 2487, 2485, 1748, 836, 834, 832, 830, 2494, 827, 2492, 843, 841,
      839, 845, 1765, 1763, 2701, 2676, 2674, 2653, 2648, 2656, 2634, 2633,
      2631, 2629, 1821, 2638, 2636, 2770, 2763, 2761, 2750, 2745, 2753,
      2736, 2735, 2733, 2731, 1848, 2740, 2738, 2786, 2784, 591, 588, 576,
      569, 566, 2296, 1590, 537, 534, 526, 2276, 522, 2274, 545, 542, 539,
      548, 1572, 1570, 481, 2245, 466, 2242, 462, 2239, 492, 485, 482, 2249,
      496, 494, 1534, 1531, 1528, 1538, 413, 2196, 406, 2191, 2188, 425,
      419, 2202, 415, 2199, 432, 430, 427, 1472, 1467, 1464, 433, 1476,
      1474, 368, 367, 2160, 365, 2159, 362, 2157, 2155, 2152, 378, 377, 375,
      2166, 372, 2165, 369, 2162, 383, 381, 379, 2168, 1419, 1418, 1416,
      1414, 385, 1411, 384, 1423, 1422, 1420, 1424, 2461, 802, 2441, 2439,
      790, 786, 783, 794, 2409, 2406, 2403, 750, 742, 738, 2414, 756, 753,
      1720, 2367, 2365, 2362, 2359, 1663, 693, 691, 684, 2373, 680, 2370,
      702, 699, 696, 704, 1690, 1687, 2337, 2336, 2334, 2332, 1624, 2329,
      1622, 640, 637, 2344, 634, 2342, 630, 2340, 650, 648, 645, 2346, 655,
      653, 1653, 1651, 1649, 1655, 2612, 2597, 2595, 2571, 2568, 2565, 2576,
      2534, 2529, 2526, 1787, 2540, 2537, 907, 904, 900, 910, 2503, 2502,
      2500, 2498, 1768, 2495, 1767, 2510, 2508, 2506, 869, 866, 863, 2513,
      876, 874, 1782, 2720, 2713, 2711, 2697, 2694, 2691, 2702, 2672, 2670,
      2664, 1828, 2678, 2675, 2647, 2646, 2644, 2642, 1823, 2639, 1822,
      2654, 2652, 2650, 2657, 2771, 1855, 2765, 2762, 1850, 1849, 2751,
      2749, 2747, 2754, 353, 2148, 344, 342, 336, 2142, 332, 2140, 345,
      1375, 1373, 306, 2130, 299, 2128, 295, 2125, 319, 314, 311, 2132,
      1354, 1352, 1349, 1356, 262, 257, 2101, 253, 2096, 2093, 274, 273,
      267, 2107, 263, 2104, 280, 278, 275, 1316, 1311, 1308, 1320, 1318,
      2052, 202, 2050, 2044, 2040, 219, 2063, 212, 2060, 208, 2055, 224,
      221, 2066, 1260, 1258, 1252, 231, 1248, 229, 1266, 1264, 1261, 1268,
      155, 1998, 153, 1996, 1994, 1991, 1988, 165, 164, 2007, 162, 2006,
      159, 2003, 2000, 172, 171, 169, 2012, 166, 2010, 1186, 1184, 1182,
      1179, 175, 1176, 173, 1192, 1191, 1189, 1187, 176, 1194, 1193, 2313,
      2307, 2305, 592, 589, 2294, 2292, 2289, 578, 572, 568, 2297, 580,
      1591, 2272, 2267, 2264, 1547, 538, 536, 529, 2278, 525, 2275, 547,
      544, 541, 1574, 1571, 2237, 2235, 2229, 1493, 2225, 1489, 478, 2247,
      470, 2244, 465, 2241, 493, 488, 484, 2250, 498, 495, 1536, 1533, 1530,
      1539, 2187, 2186, 2184, 2182, 1432, 2179, 1430, 2176, 1427, 414, 412,
      2197, 409, 2195, 405, 2193, 2190, 426, 424, 421, 2203, 418, 2201, 431,
      429, 1473, 1471, 1469, 1466, 434, 1477, 1475, 2478, 2472, 2470, 2459,
      2457, 2454, 2462, 803, 2437, 2432, 2429, 1726, 2443, 2440, 792, 789,
      785, 2401, 2399, 2393, 1702, 2389, 1699, 2411, 2408, 2405, 745, 741,
      2415, 758, 755, 1721, 2358, 2357, 2355, 2353, 1661, 2350, 1660, 2347,
      1657, 2368, 2366, 2364, 2361, 1666, 690, 687, 2374, 683, 2372, 701,
      698, 705, 1691, 1689, 2619, 2617, 2610, 2608, 2605, 2613, 2593, 2588,
      2585, 1803, 2599, 2596, 2563, 2561, 2555, 1797, 2551, 1795, 2573,
      2570, 2567, 2577, 2525, 2524, 2522, 2520, 1786, 2517, 1785, 2514,
      1783, 2535, 2533, 2531, 2528, 1788, 2541, 2539, 906, 903, 911, 2721,
      1844, 2715, 2712, 1838, 1836, 2699, 2696, 2693, 2703, 1827, 1826,
      1824, 2673, 2671, 2669, 2666, 1829, 2679, 2677, 1858, 1857, 2772,
      1854, 1853, 1851, 1856, 2766, 2764, 143, 1987, 139, 1986, 135, 133,
      131, 1984, 128, 1983, 125, 1981, 138, 137, 136, 1985, 1133, 1132,
      1130, 112, 110, 1974, 107, 1973, 104, 1971, 1969, 122, 121, 119, 117,
      1977, 114, 1976, 124, 1115, 1114, 1112, 1110, 1117, 1116, 84, 83,
      1953, 81, 1952, 78, 1950, 1948, 1945, 94, 93, 91, 1959, 88, 1958, 85,
      1955, 99, 97, 95, 1961, 1086, 1085, 1083, 1081, 1078, 100, 1090, 1089,
      1087, 1091, 49, 47, 1917, 44, 1915, 1913, 1910, 1907, 59, 1926, 56,
      1925, 53, 1922, 1919, 66, 64, 1931, 61, 1929, 1042, 1040, 1038, 71,
      1035, 70, 1032, 68, 1048, 1047, 1045, 1043, 1050, 1049, 12, 10, 1869,
      1867, 1864, 1861, 21, 1880, 19, 1877, 1874, 1871, 28, 1888, 25, 1886,
      22, 1883, 982, 980, 977, 974, 32, 30, 991, 989, 987, 984, 34, 995,
      994, 992, 2151, 2150, 2147, 2146, 2144, 356, 355, 354, 2149, 2139,
      2138, 2136, 2134, 1359, 343, 341, 338, 2143, 335, 2141, 348, 347, 346,
      1376, 1374, 2124, 2123, 2121, 2119, 1326, 2116, 1324, 310, 308, 305,
      2131, 302, 2129, 298, 2127, 320, 318, 316, 313, 2133, 322, 321, 1355,
      1353, 1351, 1357, 2092, 2091, 2089, 2087, 1276, 2084, 1274, 2081,
      1271, 259, 2102, 256, 2100, 252, 2098, 2095, 272, 269, 2108, 266,
      2106, 281, 279, 277, 1317, 1315, 1313, 1310, 282, 1321, 1319, 2039,
      2037, 2035, 2032, 1203, 2029, 1200, 1197, 207, 2053, 205, 2051, 201,
      2049, 2046, 2043, 220, 218, 2064, 215, 2062, 211, 2059, 228, 226, 223,
      2069, 1259, 1257, 1254, 232, 1251, 230, 1267, 1265, 1263, 2316, 2315,
      2312, 2311, 2309, 2314, 2304, 2303, 2301, 2299, 1593, 2308, 2306, 590,
      2288, 2287, 2285, 2283, 1578, 2280, 1577, 2295, 2293, 2291, 579, 577,
      574, 571, 2298, 582, 581, 1592, 2263, 2262, 2260, 2258, 1545, 2255,
      1544, 2252, 1541, 2273, 2271, 2269, 2266, 1550, 535, 532, 2279, 528,
      2277, 546, 543, 549, 1575, 1573, 2224, 2222, 2220, 1486, 2217, 1485,
      2214, 1482, 1479, 2238, 2236, 2234, 2231, 1496, 2228, 1492, 480, 477,
      2248, 473, 2246, 469, 2243, 490, 487, 2251, 497, 1537, 1535, 1532,
      2477, 2476, 2474, 2479, 2469, 2468, 2466, 2464, 1730, 2473, 2471,
      2453, 2452, 2450, 2448, 1729, 2445, 1728, 2460, 2458, 2456, 2463, 805,
      804, 2428, 2427, 2425, 2423, 1725, 2420, 1724, 2417, 1722, 2438, 2436,
      2434, 2431, 1727, 2444, 2442, 793, 791, 788, 795, 2388, 2386, 2384,
      1697, 2381, 1696, 2378, 1694, 1692, 2402, 2400, 2398, 2395, 1703,
      2392, 1701, 2412, 2410, 2407, 751, 748, 744, 2416, 759, 757, 1807,
      2620, 2618, 1806, 1805, 2611, 2609, 2607, 2614, 1802, 1801, 1799,
      2594, 2592, 2590, 2587, 1804, 2600, 2598, 1794, 1793, 1791, 1789,
      2564, 2562, 2560, 2557, 1798, 2554, 1796, 2574, 2572, 2569, 2578,
      1847, 1846, 2722, 1843, 1842, 1840, 1845, 2716, 2714, 1835, 1834,
      1832, 1830, 1839, 1837, 2700, 2698, 2695, 2704, 1817, 1811, 1810, 897,
      862, 1777, 829, 826, 838, 1760, 1758, 808, 2481, 1741, 1740, 1738,
      1743, 2624, 1818, 2726, 2776, 782, 740, 737, 1715, 686, 679, 695,
      1682, 1680, 639, 628, 2339, 647, 644, 1645, 1643, 1640, 1648, 602,
      600, 597, 595, 2320, 593, 2318, 609, 607, 604, 1611, 1610, 1608, 1606,
      613, 1615, 1613, 2328, 926, 924, 892, 886, 899, 857, 850, 2505, 1778,
      824, 823, 821, 819, 2488, 818, 2486, 833, 831, 828, 840, 1761, 1759,
      2649, 2632, 2630, 2746, 2734, 2732, 2782, 2781, 570, 567, 1587, 531,
      527, 523, 540, 1566, 1564, 476, 467, 463, 2240, 486, 483, 1524, 1521,
      1518, 1529, 411, 403, 2192, 399, 2189, 423, 416, 1462, 1457, 1454,
      428, 1468, 1465, 2210, 366, 363, 2158, 360, 2156, 357, 2153, 376, 373,
      370, 2163, 1410, 1409, 1407, 1405, 382, 1402, 380, 1417, 1415, 1412,
      1421, 2175, 2174, 777, 774, 771, 784, 732, 725, 722, 2404, 743, 1716,
      676, 674, 668, 2363, 665, 2360, 685, 1684, 1681, 626, 624, 622, 2335,
      620, 2333, 617, 2330, 641, 635, 649, 1646, 1644, 1642, 2566, 928, 925,
      2530, 2527, 894, 891, 888, 2501, 2499, 2496, 858, 856, 854, 851, 1779,
      2692, 2668, 2665, 2645, 2643, 2640, 2651, 2768, 2759, 2757, 2744,
      2743, 2741, 2748, 352, 1382, 340, 337, 333, 1371, 1369, 307, 300, 296,
      2126, 315, 312, 1347, 1342, 1350, 261, 258, 250, 2097, 246, 2094, 271,
      268, 264, 1306, 1301, 1298, 276, 1312, 1309, 2115, 203, 2048, 195,
      2045, 191, 2041, 213, 209, 2056, 1246, 1244, 1238, 225, 1234, 222,
      1256, 1253, 1249, 1262, 2080, 2079, 154, 1997, 150, 1995, 147, 1992,
      1989, 163, 160, 2004, 156, 2001, 1175, 1174, 1172, 1170, 1167, 170,
      1164, 167, 1185, 1183, 1180, 1177, 174, 1190, 1188, 2025, 2024, 2022,
      587, 586, 564, 559, 556, 2290, 573, 1588, 520, 518, 512, 2268, 508,
      2265, 530, 1568, 1565, 461, 457, 2233, 450, 2230, 446, 2226, 479, 471,
      489, 1526, 1523, 1520, 397, 395, 2185, 392, 2183, 389, 2180, 2177,
      410, 2194, 402, 422, 1463, 1461, 1459, 1456, 1470, 2455, 799, 2433,
      2430, 779, 776, 773, 2397, 2394, 2390, 734, 728, 724, 746, 1717, 2356,
      2354, 2351, 2348, 1658, 677, 675, 673, 670, 667, 688, 1685, 1683,
      2606, 2589, 2586, 2559, 2556, 2552, 927, 2523, 2521, 2518, 2515, 1784,
      2532, 895, 893, 890, 2718, 2709, 2707, 2689, 2687, 2684, 2663, 2662,
      2660, 2658, 1825, 2667, 2769, 1852, 2760, 2758, 142, 141, 1139, 1138,
      134, 132, 129, 126, 1982, 1129, 1128, 1126, 1131, 113, 111, 108, 105,
      1972, 101, 1970, 120, 118, 115, 1109, 1108, 1106, 1104, 123, 1113,
      1111, 82, 79, 1951, 75, 1949, 72, 1946, 92, 89, 86, 1956, 1077, 1076,
      1074, 1072, 98, 1069, 96, 1084, 1082, 1079, 1088, 1968, 1967, 48, 45,
      1916, 42, 1914, 39, 1911, 1908, 60, 57, 54, 1923, 50, 1920, 1031,
      1030, 1028, 1026, 67, 1023, 65, 1020, 62, 1041, 1039, 1036, 1033, 69,
      1046, 1044, 1944, 1943, 1941, 11, 9, 1868, 7, 1865, 1862, 1859, 20,
      1878, 16, 1875, 13, 1872, 970, 968, 966, 963, 29, 960, 26, 23, 983,
      981, 978, 975, 33, 971, 31, 990, 988, 985, 1906, 1904, 1902, 993, 351,
      2145, 1383, 331, 330, 328, 326, 2137, 323, 2135, 339, 1372, 1370, 294,
      293, 291, 289, 2122, 286, 2120, 283, 2117, 309, 303, 317, 1348, 1346,
      1344, 245, 244, 242, 2090, 239, 2088, 236, 2085, 2082, 260, 2099, 249,
      270, 1307, 1305, 1303, 1300, 1314, 189, 2038, 186, 2036, 183, 2033,
      2030, 2026, 206, 198, 2047, 194, 216, 1247, 1245, 1243, 1240, 227,
      1237, 1255, 2310, 2302, 2300, 2286, 2284, 2281, 565, 563, 561, 558,
      575, 1589, 2261, 2259, 2256, 2253, 1542, 521, 519, 517, 514, 2270,
      511, 533, 1569, 1567, 2223, 2221, 2218, 2215, 1483, 2211, 1480, 459,
      456, 453, 2232, 449, 474, 491, 1527, 1525, 1522, 2475, 2467, 2465,
      2451, 2449, 2446, 801, 800, 2426, 2424, 2421, 2418, 1723, 2435, 780,
      778, 775, 2387, 2385, 2382, 2379, 1695, 2375, 1693, 2396, 735, 733,
      730, 727, 749, 1718, 2616, 2615, 2604, 2603, 2601, 2584, 2583, 2581,
      2579, 1800, 2591, 2550, 2549, 2547, 2545, 1792, 2542, 1790, 2558, 929,
      2719, 1841, 2710, 2708, 1833, 1831, 2690, 2688, 2686, 1815, 1809,
      1808, 1774, 1756, 1754, 1737, 1736, 1734, 1739, 1816, 1711, 1676,
      1674, 633, 629, 1638, 1636, 1633, 1641, 598, 1605, 1604, 1602, 1600,
      605, 1609, 1607, 2327, 887, 853, 1775, 822, 820, 1757, 1755, 1584,
      524, 1560, 1558, 468, 464, 1514, 1511, 1508, 1519, 408, 404, 400,
      1452, 1447, 1444, 417, 1458, 1455, 2208, 364, 361, 358, 2154, 1401,
      1400, 1398, 1396, 374, 1393, 371, 1408, 1406, 1403, 1413, 2173, 2172,
      772, 726, 723, 1712, 672, 669, 666, 682, 1678, 1675, 625, 623, 621,
      618, 2331, 636, 632, 1639, 1637, 1635, 920, 918, 884, 880, 889, 849,
      848, 847, 846, 2497, 855, 852, 1776, 2641, 2742, 2787, 1380, 334,
      1367, 1365, 301, 297, 1340, 1338, 1335, 1343, 255, 251, 247, 1296,
      1291, 1288, 265, 1302, 1299, 2113, 204, 196, 192, 2042, 1232, 1230,
      1224, 214, 1220, 210, 1242, 1239, 1235, 1250, 2077, 2075, 151, 148,
      1993, 144, 1990, 1163, 1162, 1160, 1158, 1155, 161, 1152, 157, 1173,
      1171, 1168, 1165, 168, 1181, 1178, 2021, 2020, 2018, 2023, 585, 560,
      557, 1585, 516, 509, 1562, 1559, 458, 447, 2227, 472, 1516, 1513,
      1510, 398, 396, 393, 390, 2181, 386, 2178, 407, 1453, 1451, 1449,
      1446, 420, 1460, 2209, 769, 764, 720, 712, 2391, 729, 1713, 664, 663,
      661, 659, 2352, 656, 2349, 671, 1679, 1677, 2553, 922, 919, 2519,
      2516, 885, 883, 881, 2685, 2661, 2659, 2767, 2756, 2755, 140, 1137,
      1136, 130, 127, 1125, 1124, 1122, 1127, 109, 106, 102, 1103, 1102,
      1100, 1098, 116, 1107, 1105, 1980, 80, 76, 73, 1947, 1068, 1067, 1065,
      1063, 90, 1060, 87, 1075, 1073, 1070, 1080, 1966, 1965, 46, 43, 40,
      1912, 36, 1909, 1019, 1018, 1016, 1014, 58, 1011, 55, 1008, 51, 1029,
      1027, 1024, 1021, 63, 1037, 1034, 1940, 1939, 1937, 1942, 8, 1866, 4,
      1863, 1, 1860, 956, 954, 952, 949, 946, 17, 14, 969, 967, 964, 961,
      27, 957, 24, 979, 976, 972, 1901, 1900, 1898, 1896, 986, 1905, 1903,
      350, 349, 1381, 329, 327, 324, 1368, 1366, 292, 290, 287, 284, 2118,
      304, 1341, 1339, 1337, 1345, 243, 240, 237, 2086, 233, 2083, 254,
      1297, 1295, 1293, 1290, 1304, 2114, 190, 187, 184, 2034, 180, 2031,
      177, 2027, 199, 1233, 1231, 1229, 1226, 217, 1223, 1241, 2078, 2076,
      584, 555, 554, 552, 550, 2282, 562, 1586, 507, 506, 504, 502, 2257,
      499, 2254, 515, 1563, 1561, 445, 443, 441, 2219, 438, 2216, 435, 2212,
      460, 454, 475, 1517, 1515, 1512, 2447, 798, 797, 2422, 2419, 770, 768,
      766, 2383, 2380, 2376, 721, 719, 717, 714, 731, 1714, 2602, 2582,
      2580, 2548, 2546, 2543, 923, 921, 2717, 2706, 2705, 2683, 2682, 2680,
      1771, 1752, 1750, 1733, 1732, 1731, 1735, 1814, 1707, 1670, 1668,
      1631, 1629, 1626, 1634, 1599, 1598, 1596, 1594, 1603, 1601, 2326,
      1772, 1753, 1751, 1581, 1554, 1552, 1504, 1501, 1498, 1509, 1442,
      1437, 1434, 401, 1448, 1445, 2206, 1392, 1391, 1389, 1387, 1384, 359,
      1399, 1397, 1394, 1404, 2171, 2170, 1708, 1672, 1669, 619, 1632, 1630,
      1628, 1773, 1378, 1363, 1361, 1333, 1328, 1336, 1286, 1281, 1278, 248,
      1292, 1289, 2111, 1218, 1216, 1210, 197, 1206, 193, 1228, 1225, 1221,
      1236, 2073, 2071, 1151, 1150, 1148, 1146, 152, 1143, 149, 1140, 145,
      1161, 1159, 1156, 1153, 158, 1169, 1166, 2017, 2016, 2014, 2019, 1582,
      510, 1556, 1553, 452, 448, 1506, 1500, 394, 391, 387, 1443, 1441,
      1439, 1436, 1450, 2207, 765, 716, 713, 1709, 662, 660, 657, 1673,
      1671, 916, 914, 879, 878, 877, 882, 1135, 1134, 1121, 1120, 1118,
      1123, 1097, 1096, 1094, 1092, 103, 1101, 1099, 1979, 1059, 1058, 1056,
      1054, 77, 1051, 74, 1066, 1064, 1061, 1071, 1964, 1963, 1007, 1006,
      1004, 1002, 999, 41, 996, 37, 1017, 1015, 1012, 1009, 52, 1025, 1022,
      1936, 1935, 1933, 1938, 942, 940, 938, 935, 932, 5, 2, 955, 953, 950,
      947, 18, 943, 15, 965, 962, 958, 1895, 1894, 1892, 1890, 973, 1899,
      1897, 1379, 325, 1364, 1362, 288, 285, 1334, 1332, 1330, 241, 238,
      234, 1287, 1285, 1283, 1280, 1294, 2112, 188, 185, 181, 178, 2028,
      1219, 1217, 1215, 1212, 200, 1209, 1227, 2074, 2072, 583, 553, 551,
      1583, 505, 503, 500, 513, 1557, 1555, 444, 442, 439, 436, 2213, 455,
      451, 1507, 1505, 1502, 796, 763, 762, 760, 767, 711, 710, 708, 706,
      2377, 718, 715, 1710, 2544, 917, 915, 2681, 1627, 1597, 1595, 2325,
      1769, 1749, 1747, 1499, 1438, 1435, 2204, 1390, 1388, 1385, 1395,
      2169, 2167, 1704, 1665, 1662, 1625, 1623, 1620, 1770, 1329, 1282,
      1279, 2109, 1214, 1207, 1222, 2068, 2065, 1149, 1147, 1144, 1141, 146,
      1157, 1154, 2013, 2011, 2008, 2015, 1579, 1549, 1546, 1495, 1487,
      1433, 1431, 1428, 1425, 388, 1440, 2205, 1705, 658, 1667, 1664, 1119,
      1095, 1093, 1978, 1057, 1055, 1052, 1062, 1962, 1960, 1005, 1003,
      1000, 997, 38, 1013, 1010, 1932, 1930, 1927, 1934, 941, 939, 936, 933,
      6, 930, 3, 951, 948, 944, 1889, 1887, 1884, 1881, 959, 1893, 1891, 35,
      1377, 1360, 1358, 1327, 1325, 1322, 1331, 1277, 1275, 1272, 1269, 235,
      1284, 2110, 1205, 1204, 1201, 1198, 182, 1195, 179, 1213, 2070, 2067,
      1580, 501, 1551, 1548, 440, 437, 1497, 1494, 1490, 1503, 761, 709,
      707, 1706, 913, 912, 2198, 1386, 2164, 2161, 1621, 1766, 2103, 1208,
      2058, 2054, 1145, 1142, 2005, 2002, 1999, 2009, 1488, 1429, 1426,
      2200, 1698, 1659, 1656, 1975, 1053, 1957, 1954, 1001, 998, 1924, 1921,
      1918, 1928, 937, 934, 931, 1879, 1876, 1873, 1870, 945, 1885, 1882,
      1323, 1273, 1270, 2105, 1202, 1199, 1196, 1211, 2061, 2057, 1576,
      1543, 1540, 1484, 1481, 1478, 1491, 1700};
}
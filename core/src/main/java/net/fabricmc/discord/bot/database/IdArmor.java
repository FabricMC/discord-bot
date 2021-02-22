/*
 * Copyright (c) 2021 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.discord.bot.database;

/**
 * Utility to armor IDs with a check digit to avoid typos.
 *
 * <p>This implements Damm's algorithm. The encoded form has one extra base10 digit at the least significant
 * position, all other digits remain unchanged.
 */
public final class IdArmor {
	public static int encode(int n) {
		if (n < 0 || n > (Integer.MAX_VALUE - 9) / 10) throw new ArithmeticException("out of bounds input: "+n);

		return (int) encode((long) n);
	}

	public static long encode(long n) {
		if (n < 0 || n > (Long.MAX_VALUE - 9) / 10) throw new ArithmeticException("out of bounds input: "+n);
		if (n == 0) return 0;

		long rem = n;
		int interim = 0;

		for (int i = floorLog10(n); i >= 0; i--) {
			long div = pow10[i];

			int digit = (int) (rem / div);
			rem -= digit * div;

			interim = opTable[interim * 10 + digit];
		}

		return n * 10 + interim;
	}

	public static int encodeOptional(int n) {
		return n < 0 ? n : encode(n);
	}

	public static int decode(int n) {
		return (int) decode((long) n);
	}

	public static long decode(long n) {
		if (n < 0) throw new ArithmeticException("out of bounds input: "+n);
		if (n == 0) return 0;

		long rem = n;
		int interim = 0;

		for (int i = floorLog10(n); i >= 0; i--) {
			long div = pow10[i];

			int digit = (int) (rem / div);
			rem -= digit * div;

			interim = opTable[interim * 10 + digit];
		}

		return interim != 0 ? -1 : n / 10;
	}

	public static int decodeOrThrow(int n, String desc) {
		int ret = decode(n);
		if (ret < 0) throw new IllegalArgumentException("invalid "+desc);

		return ret;
	}

	public static int decodeOptionalOrThrow(int n, String desc) {
		return n <= 0 ? n : decodeOrThrow(n, desc);
	}

	private static int floorLog10(long n) {
		int ret = log10Floors[Long.numberOfLeadingZeros(n)];

		return Long.compareUnsigned(n, pow10[ret + 1]) >= 0 ? ret + 1 : ret; // this works because ret is at most 1 too small
	}

	private static final byte[] log10Floors = new byte[] { // floor(log10(n_i)) with n_i = 2^(63-i) = 2^63, 2^62, ..., 2^0
			18, 18, 18, 18, 17, 17, 17, 16,
			16, 16, 15, 15, 15, 15, 14, 14,
			14, 13, 13, 13, 12, 12, 12, 12,
			11, 11, 11, 10, 10, 10, 9, 9,
			9, 9, 8, 8, 8, 7, 7, 7,
			6, 6, 6, 6, 5, 5, 5, 4,
			4, 4, 3, 3, 3, 3, 2, 2,
			2, 1, 1, 1, 0, 0, 0, 0
	};

	private static final long[] pow10 = new long[] { // 10^i
			1L, 10L, 100L, 1000L,
			10000L, 100000L, 1000000L, 10000000L,
			100000000L, 1000000000L, 10000000000L, 100000000000L,
			1000000000000L, 10000000000000L, 100000000000000L, 1000000000000000L,
			10000000000000000L, 100000000000000000L, 1000000000000000000L, -8446744073709551616L
	};

	private static final byte[] opTable = new byte[] {
			0, 3, 1, 7, 5, 9, 8, 6, 4, 2,
			7, 0, 9, 2, 1, 5, 4, 8, 6, 3,
			4, 2, 0, 6, 8, 7, 1, 3, 5, 9,
			1, 7, 5, 0, 9, 8, 3, 4, 2, 6,
			6, 1, 2, 3, 0, 4, 5, 9, 7, 8,
			3, 6, 7, 4, 2, 0, 9, 5, 8, 1,
			5, 8, 6, 9, 7, 2, 0, 1, 3, 4,
			8, 9, 4, 5, 3, 6, 2, 0, 1, 7,
			9, 4, 3, 8, 6, 1, 7, 2, 0, 5,
			2, 5, 8, 1, 4, 3, 6, 7, 9, 0
	};
}

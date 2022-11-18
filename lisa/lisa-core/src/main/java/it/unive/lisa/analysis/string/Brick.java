package it.unive.lisa.analysis.string;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

public class Brick {
	private final int min;
	private final int max;
	private final Collection<String> strings;

	public Brick(int min, int max, Collection<String> strings) {
		this.min = min;
		this.max = max;
		this.strings = strings;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Brick brick = (Brick) o;
		return min == brick.min && max == brick.max && Objects.equals(strings, brick.strings);
	}

	@Override
	public int hashCode() {
		return Objects.hash(min, max, strings);
	}

	public int getMin() {
		return min;
	}

	public int getMax() {
		return max;
	}

	public Collection<String> getStrings() {
		return strings;
	}

	public Collection<String> getReps() {
		HashSet<String> reps = new HashSet<>();

		this.recGetReps(reps, "");

		return reps;
	}

	private void recGetReps(HashSet<String> reps, String currentStr) { // Seems to be correct, needs further testing
		if (currentStr.length() > this.max) {
			reps.add(currentStr);
			return;
		} else {
			for (String string : this.strings) {
				for (int i = this.min; i < this.max; i++) {
					if(currentStr != "" || this.min != 0)
						reps.add(currentStr);

					recGetReps(reps, currentStr + string);
				}
			}
		}
	}
}

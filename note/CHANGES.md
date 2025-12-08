Changes made in this editing session

Summary
- Fixed XLSX multi-sheet import issues and UI consistency.
- Preserved workbook sheet order during XLSX parsing.
- Ensured newly imported files are collapsed by default.
- Removed noisy debug logging added during investigation.
- Adjusted LiveData update in `HomeViewModel.save(...)` to set value synchronously on the main thread to avoid lost updates when merging parsed sheets.
- Improved `CourseAdapter` to compute and cache sheet counts per file so the file badge matches the adapter display.

Files edited (high level)
- app/src/main/java/com/pku/or/courseassistant/home/HomeFragment.java
  - Added robust pending-sheet processing, fixed merge logic, removed temporary debug logs.
- app/src/main/java/com/pku/or/courseassistant/home/XlsxLightParser.java
  - Parse workbook.xml and workbook.xml.rels to preserve sheet display names and sheet order.
- app/src/main/java/com/pku/or/courseassistant/home/HomeViewModel.java
  - Use `setValue` on main thread to synchronously update LiveData.
- app/src/main/java/com/pku/or/courseassistant/home/CourseAdapter.java
  - Cache sheet counts per file to keep badge in sync with display; added programmatic collapse helpers; removed debug logs.

Notes
- I removed temporary Log.e debug statements that were added to help diagnose import ordering/count issues. The remaining Log.e usages are genuine error logs (exceptions) and were left intact.
- I did not run a full Gradle assemble in this environment. Please run a local build and verify the app on a device/emulator:

  cd /Users/hui/pku/or/CourseAssistant/code
  ./gradlew assembleDebug

- If you'd like, I can produce a single commit message for you to use locally, or create a patch file. Tell me which you prefer.

If you want me to also:
- Convert per-sheet saves to a single batch save after all sheets are parsed (recommended for performance), or
- Create a Git commit message / patch file,
say so and I'll do it next.
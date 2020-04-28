Steep User Interface
====================

This directory contains the web-based user interface of Steep. It can be started
in standalone mode or together with Steep.

Standalone mode
---------------

Steep must be running on http://localhost:8080. The address can be configured
in the `next.config.js` file if necessary.

Prepare the user interface:

    npm i

Run the user interface in development mode with hot replacement:

    npm run dev

Open http://localhost:3000 in your browser to visit the user interface.

Build the user interface:

    npm run build

Building also runs `eslint` to check the code for common errors and style
issues. You can run the linter separately with:

    npm run lint

Run with Steep
--------------

Change into the root directory of this repository and run Steep as usual with

    ./gradlew run

The project's main gradle build file loads the `build.gradle.kts` file from
this directory here to perform the necessary build steps.

Finally, open http://localhost:8080 in your browser.

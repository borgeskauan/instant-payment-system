#!/bin/bash

set -euo pipefail

# Configuration
readonly SCRIPT_NAME="$(basename "$0")"
readonly SUMMARY_DIR="summary"

# Global variables
TARGET_FOLDER=""

# Function to display usage information
usage() {
    echo "Usage: $SCRIPT_NAME <run-tag>"
    echo "  run-tag: Identifier for the test run"
}

# Function to display error messages and exit
error_exit() {
    echo "Error: $1" >&2
    exit 1
}

# Function to extract counter from folder name
extract_counter() {
    local folder="$1"
    if [[ "$folder" =~ ^([0-9]+)-.* ]]; then
        echo "${BASH_REMATCH[1]}"
    else
        echo "0"
    fi
}

# Function to setup target folder
setup_target_folder() {
    local run_tag="$1"
    local existing_folder=""
    local next_counter=1

    # Create summary directory if it doesn't exist
    if [[ ! -d "$SUMMARY_DIR" ]]; then
        if ! mkdir -p "$SUMMARY_DIR"; then
            error_exit "Failed to create summary directory '$SUMMARY_DIR'"
        fi
        echo "Created summary directory: $SUMMARY_DIR"
    fi

    # Find existing folders and determine next counter
    if [[ -d "$SUMMARY_DIR" ]]; then
        while IFS= read -r -d '' dir; do
            local dir_name=$(basename "$dir")
            local counter=$(extract_counter "$dir_name")
            local actual_tag="${dir_name#*-}"

            if [[ "$actual_tag" == "$run_tag" ]]; then
                existing_folder="$dir"
            fi

            if [[ "$counter" -ge "$next_counter" ]]; then
                next_counter=$((counter + 1))
            fi
        done < <(find "$SUMMARY_DIR" -maxdepth 1 -type d -name "[0-9]*-*" -print0 2>/dev/null)
    fi

    # Set target folder
    if [[ -n "$existing_folder" ]]; then
        TARGET_FOLDER="$existing_folder"
        echo "Using existing folder: $TARGET_FOLDER"
    else
        local folder_name="${next_counter}-${run_tag}"
        TARGET_FOLDER="$SUMMARY_DIR/$folder_name"

        if ! mkdir -p "$TARGET_FOLDER"; then
            error_exit "Failed to create directory '$TARGET_FOLDER'"
        fi
        echo "Created new folder: $TARGET_FOLDER"
    fi
}

# Function to run k6 test
run_k6_test() {
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local output_file="$TARGET_FOLDER/${timestamp}.json"

    echo "Starting k6 test..."
    if ! k6 run --summary-export="$output_file" spi-test.js; then
        error_exit "k6 test execution failed"
    fi

    echo "Test completed successfully. Results saved to: $output_file"
}

# Main execution
main() {
    # Validate input
    if [[ $# -eq 0 ]]; then
        usage
        error_exit "Run tag is required."
    fi

    local run_tag="$1"

    # Validate run tag format (basic check)
    if [[ ! "$run_tag" =~ ^[a-zA-Z0-9._-]+$ ]]; then
        error_exit "Invalid run tag format. Use only alphanumeric characters, dots, dashes, and underscores."
    fi

    setup_target_folder "$run_tag"
    run_k6_test
}

main "$@"
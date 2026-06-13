#!/bin/bash

set -euo pipefail

# Configuration
readonly SCRIPT_NAME="$(basename "$0")"
readonly SUMMARY_DIR="summary"
readonly KAFKA_LAG_SAMPLE_INTERVAL_SECONDS=2

# Global variables
TARGET_FOLDER=""
KAFKA_LAG_SAMPLER_PID=""
ENABLE_KAFKA_LAG_SAMPLING=true
ENABLE_FUNDS_PROVISIONING=true
RUN_TAG=""

# Function to display usage information
usage() {
    echo "Usage: $SCRIPT_NAME [--kafka-lag|--no-kafka-lag] [--provision-funds|--no-provision-funds] <run-tag>"
    echo "  run-tag: Identifier for the test run"
    echo
    echo "Options:"
    echo "  --kafka-lag           Enable Kafka lag sampling (default)"
    echo "  --no-kafka-lag        Disable Kafka lag sampling"
    echo "  --provision-funds     Provision deterministic load-test funds before running k6 (default)"
    echo "  --no-provision-funds  Disable funds provisioning"
}

# Function to display error messages and exit
error_exit() {
    echo "Error: $1" >&2
    exit 1
}

# Function to stop the Kafka lag sampler if it is running
stop_kafka_lag_sampler() {
    if [[ -n "$KAFKA_LAG_SAMPLER_PID" ]] && kill -0 "$KAFKA_LAG_SAMPLER_PID" 2>/dev/null; then
        kill "$KAFKA_LAG_SAMPLER_PID" 2>/dev/null || true
        wait "$KAFKA_LAG_SAMPLER_PID" 2>/dev/null || true
    fi

    KAFKA_LAG_SAMPLER_PID=""
}

# Function to turn the pseudo-terminal capture into readable text
write_clean_console_output() {
    local raw_output_file="$1"
    local clean_output_file="$2"

    perl -pe 's/\e\[[0-9;?]*[ -\/]*[@-~]//g; s/\r//g; s/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]//g' "$raw_output_file" \
        | awk '
            /^Script started on / { next }
            /^Script done on / { next }
            /^running \(/ { next }
            /^cold_psps[[:space:]]/ { next }
            /^hot_psps[[:space:]]/ { next }
            {
                sub(/[[:space:]]+$/, "")
                if ($0 == "") {
                    if (!blank) {
                        print ""
                    }
                    blank = 1
                    next
                }
                print
                blank = 0
            }
        ' > "$clean_output_file"
}

# Function to parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --kafka-lag)
                ENABLE_KAFKA_LAG_SAMPLING=true
                shift
                ;;
            --no-kafka-lag)
                ENABLE_KAFKA_LAG_SAMPLING=false
                shift
                ;;
            --provision-funds)
                ENABLE_FUNDS_PROVISIONING=true
                shift
                ;;
            --no-provision-funds)
                ENABLE_FUNDS_PROVISIONING=false
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            --*)
                usage
                error_exit "Unknown option: $1"
                ;;
            *)
                if [[ -n "$RUN_TAG" ]]; then
                    usage
                    error_exit "Only one run tag is allowed."
                fi
                RUN_TAG="$1"
                shift
                ;;
        esac
    done

    if [[ -z "$RUN_TAG" ]]; then
        usage
        error_exit "Run tag is required."
    fi
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
    local run_folder="$TARGET_FOLDER/${timestamp}"
    local output_file="$run_folder/summary.json"
    local console_output_file="$run_folder/output.txt"
    local raw_console_output_file="$run_folder/.output.raw.txt"
    local kafka_lag_file="$run_folder/kafka-lag.csv"
    local kafka_lag_log_file="$run_folder/kafka-lag.log"
    local funds_provisioning_log_file="$run_folder/provision-funds.log"
    local k6_command=""
    local k6_exit_code=0

    if ! mkdir -p "$run_folder"; then
        error_exit "Failed to create run directory '$run_folder'"
    fi

    echo "Run artifacts folder: $run_folder"

    if [[ "$ENABLE_FUNDS_PROVISIONING" == true ]]; then
        echo "Provisioning load-test settlement accounts..."
        if ! ./provision-funds.sh > "$funds_provisioning_log_file" 2>&1; then
            echo "Funds provisioning failed. Log output:" >&2
            cat "$funds_provisioning_log_file" >&2
            error_exit "Funds provisioning failed"
        fi
        echo "Funds provisioning log saved to: $funds_provisioning_log_file"
    else
        echo "Funds provisioning disabled."
    fi

    if [[ "$ENABLE_KAFKA_LAG_SAMPLING" == true ]]; then
        echo "Starting Kafka lag sampler..."
        ./sample-kafka-lag.sh "$KAFKA_LAG_SAMPLE_INTERVAL_SECONDS" "" "$kafka_lag_file" > "$kafka_lag_log_file" 2>&1 &
        KAFKA_LAG_SAMPLER_PID=$!
        sleep 1

        if ! kill -0 "$KAFKA_LAG_SAMPLER_PID" 2>/dev/null; then
            echo "Kafka lag sampler failed to start. Log output:" >&2
            cat "$kafka_lag_log_file" >&2
            KAFKA_LAG_SAMPLER_PID=""
            error_exit "Kafka lag sampler failed"
        fi
    else
        echo "Kafka lag sampler disabled."
    fi

    echo "Starting k6 test..."
    printf -v k6_command 'k6 run --summary-export=%q spi-test.js' "$output_file"
    if script -q -e -c "$k6_command" "$raw_console_output_file"; then
        k6_exit_code=0
    else
        k6_exit_code=$?
    fi

    write_clean_console_output "$raw_console_output_file" "$console_output_file"
    rm -f "$raw_console_output_file"

    if [[ "$k6_exit_code" -ne 0 ]]; then
        stop_kafka_lag_sampler
        if [[ "$ENABLE_KAFKA_LAG_SAMPLING" == true ]]; then
            echo "Kafka lag samples saved to: $kafka_lag_file"
        fi
        echo "Console output saved to: $console_output_file"
        error_exit "k6 test execution failed"
    fi

    stop_kafka_lag_sampler
    echo "Test completed successfully. Results saved to: $output_file"
    echo "Console output saved to: $console_output_file"
    if [[ "$ENABLE_KAFKA_LAG_SAMPLING" == true ]]; then
        echo "Kafka lag samples saved to: $kafka_lag_file"
    fi
}

# Function to check required command-line tools
check_required_commands() {
    if ! command -v script &>/dev/null; then
        error_exit "'script' command not found. Install util-linux so k6 output can be captured without changing terminal behavior."
    fi

    if ! command -v perl &>/dev/null; then
        error_exit "'perl' command not found. It is required to clean the captured k6 terminal output."
    fi
}

# Function to check k6 version meets minimum requirement
check_k6_version() {
    local required_major=0
    local required_minor=49

    if ! command -v k6 &>/dev/null; then
        error_exit "k6 not found. Install k6 >= 0.${required_minor}.0 from https://k6.io/docs/get-started/installation/"
    fi

    local version_string
    version_string=$(k6 version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

    if [[ -z "$version_string" ]]; then
        echo "Warning: Could not determine k6 version. Proceeding anyway."
        return
    fi

    local major minor
    major=$(echo "$version_string" | cut -d. -f1)
    minor=$(echo "$version_string" | cut -d. -f2)

    if [[ "$major" -lt "$required_major" ]] || \
       { [[ "$major" -eq "$required_major" ]] && [[ "$minor" -lt "$required_minor" ]]; }; then
        error_exit "k6 ${version_string} is too old. gRPC streaming requires k6 >= 0.${required_minor}.0 (k6/net/grpc module)."
    fi

    echo "k6 version ${version_string} — OK"
}

# Main execution
main() {
    parse_args "$@"

    # Validate run tag format (basic check)
    if [[ ! "$RUN_TAG" =~ ^[a-zA-Z0-9._-]+$ ]]; then
        error_exit "Invalid run tag format. Use only alphanumeric characters, dots, dashes, and underscores."
    fi

    check_k6_version
    check_required_commands
    setup_target_folder "$RUN_TAG"
    trap stop_kafka_lag_sampler EXIT
    run_k6_test
}

main "$@"

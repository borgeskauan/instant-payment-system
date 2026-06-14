#!/bin/bash

set -euo pipefail

# Configuration
readonly SCRIPT_NAME="$(basename "$0")"
readonly SUMMARY_DIR="summary"
readonly KAFKA_LAG_SAMPLE_INTERVAL_SECONDS=2
readonly SYSTEM_STATS_SAMPLE_INTERVAL_SECONDS=5
readonly SYSTEM_STATS_PRE_TEST_SECONDS=10
readonly PROCESS_STATS_SAMPLE_INTERVAL_SECONDS=5
readonly PROCESS_STATS_TOP_N=20
readonly JFR_CONTAINER_NAME="spi"
readonly JFR_RECORDING_NAME="spi-load-test"
readonly JFR_DEFAULT_DELAY="5s"
readonly JFR_DEFAULT_DURATION="120s"

# Global variables
TARGET_FOLDER=""
KAFKA_LAG_SAMPLER_PID=""
SYSTEM_STATS_SAMPLER_PID=""
PROCESS_STATS_SAMPLER_PID=""
ENABLE_KAFKA_LAG_SAMPLING=true
ENABLE_SYSTEM_STATS_SAMPLING=true
ENABLE_PROCESS_STATS_SAMPLING=true
ENABLE_FUNDS_PROVISIONING=true
ENABLE_JFR=true
JFR_DELAY="$JFR_DEFAULT_DELAY"
JFR_DURATION="$JFR_DEFAULT_DURATION"
ACTIVE_JFR_CONTAINER_FILE=""
ACTIVE_JFR_OUTPUT_FILE=""
ACTIVE_JFR_LOG_FILE=""
RUN_TAG=""

# Function to display usage information
usage() {
    echo "Usage: $SCRIPT_NAME [--kafka-lag|--no-kafka-lag] [--system-stats|--no-system-stats] [--process-stats|--no-process-stats] [--provision-funds|--no-provision-funds] [--jfr|--no-jfr] [--jfr-delay <duration>] [--jfr-duration <duration>] <run-tag>"
    echo "  run-tag: Identifier for the test run"
    echo
    echo "Options:"
    echo "  --kafka-lag           Enable Kafka lag sampling (default)"
    echo "  --no-kafka-lag        Disable Kafka lag sampling"
    echo "  --system-stats        Enable host/container stats sampling (default)"
    echo "  --no-system-stats     Disable host/container stats sampling"
    echo "  --process-stats       Enable top host process stats sampling (default)"
    echo "  --no-process-stats    Disable top host process stats sampling"
    echo "  --provision-funds     Provision deterministic load-test funds before running k6 (default)"
    echo "  --no-provision-funds  Disable funds provisioning"
    echo "  --jfr                 Record a Java Flight Recorder profile from the spi container (default)"
    echo "  --no-jfr              Disable Java Flight Recorder profiling"
    echo "  --jfr-delay VALUE     Delay before JFR recording starts (default: $JFR_DEFAULT_DELAY)"
    echo "  --jfr-duration VALUE  Maximum JFR recording duration (default: $JFR_DEFAULT_DURATION)"
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

# Function to stop the system stats sampler if it is running
stop_system_stats_sampler() {
    if [[ -n "$SYSTEM_STATS_SAMPLER_PID" ]] && kill -0 "$SYSTEM_STATS_SAMPLER_PID" 2>/dev/null; then
        kill "$SYSTEM_STATS_SAMPLER_PID" 2>/dev/null || true
        wait "$SYSTEM_STATS_SAMPLER_PID" 2>/dev/null || true
    fi

    SYSTEM_STATS_SAMPLER_PID=""
}

# Function to stop the process stats sampler if it is running
stop_process_stats_sampler() {
    if [[ -n "$PROCESS_STATS_SAMPLER_PID" ]] && kill -0 "$PROCESS_STATS_SAMPLER_PID" 2>/dev/null; then
        kill "$PROCESS_STATS_SAMPLER_PID" 2>/dev/null || true
        wait "$PROCESS_STATS_SAMPLER_PID" 2>/dev/null || true
    fi

    PROCESS_STATS_SAMPLER_PID=""
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
            --system-stats)
                ENABLE_SYSTEM_STATS_SAMPLING=true
                shift
                ;;
            --no-system-stats)
                ENABLE_SYSTEM_STATS_SAMPLING=false
                shift
                ;;
            --process-stats)
                ENABLE_PROCESS_STATS_SAMPLING=true
                shift
                ;;
            --no-process-stats)
                ENABLE_PROCESS_STATS_SAMPLING=false
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
            --jfr)
                ENABLE_JFR=true
                shift
                ;;
            --no-jfr)
                ENABLE_JFR=false
                shift
                ;;
            --jfr-delay)
                if [[ $# -lt 2 || "$2" == --* ]]; then
                    usage
                    error_exit "--jfr-delay requires a value, for example: 5s"
                fi
                JFR_DELAY="$2"
                shift 2
                ;;
            --jfr-duration)
                if [[ $# -lt 2 || "$2" == --* ]]; then
                    usage
                    error_exit "--jfr-duration requires a value, for example: 120s"
                fi
                JFR_DURATION="$2"
                shift 2
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

# Function to start a Java Flight Recorder capture in the SPI container
start_jfr_recording() {
    local container_file="$1"
    local output_file="$2"
    local jfr_log_file="$3"

    echo "Starting SPI JFR recording..."

    if ! docker exec "$JFR_CONTAINER_NAME" sh -c 'command -v jcmd >/dev/null' > "$jfr_log_file" 2>&1; then
        cat "$jfr_log_file" >&2
        error_exit "jcmd not found in '$JFR_CONTAINER_NAME'. Use a JDK runtime image for SPI and rebuild the container."
    fi

    if ! docker exec "$JFR_CONTAINER_NAME" jcmd 1 JFR.start \
        name="$JFR_RECORDING_NAME" \
        settings=profile \
        delay="$JFR_DELAY" \
        duration="$JFR_DURATION" \
        filename="$container_file" \
        dumponexit=true >> "$jfr_log_file" 2>&1; then
        cat "$jfr_log_file" >&2
        error_exit "Failed to start JFR recording in '$JFR_CONTAINER_NAME'"
    fi

    ACTIVE_JFR_CONTAINER_FILE="$container_file"
    ACTIVE_JFR_OUTPUT_FILE="$output_file"
    ACTIVE_JFR_LOG_FILE="$jfr_log_file"

    echo "JFR recording started. Log saved to: $jfr_log_file"
}

# Function to stop a Java Flight Recorder capture and copy it to the run folder
finish_jfr_recording() {
    local container_file="$1"
    local output_file="$2"
    local jfr_log_file="$3"

    if [[ "$ENABLE_JFR" != true ]]; then
        return
    fi

    echo "Stopping SPI JFR recording..."

    if ! docker exec "$JFR_CONTAINER_NAME" jcmd 1 JFR.stop \
        name="$JFR_RECORDING_NAME" \
        filename="$container_file" >> "$jfr_log_file" 2>&1; then
        echo "Warning: Failed to stop JFR recording. It may have already ended; trying to copy the recording file." | tee -a "$jfr_log_file" >&2
    fi

    if docker cp "${JFR_CONTAINER_NAME}:${container_file}" "$output_file" >> "$jfr_log_file" 2>&1; then
        echo "SPI JFR recording saved to: $output_file"
    else
        echo "Warning: Failed to copy SPI JFR recording from ${JFR_CONTAINER_NAME}:${container_file}. See: $jfr_log_file" >&2
    fi

    ACTIVE_JFR_CONTAINER_FILE=""
    ACTIVE_JFR_OUTPUT_FILE=""
    ACTIVE_JFR_LOG_FILE=""
}

# Function to stop the active Java Flight Recorder capture if one is running
finish_active_jfr_recording() {
    if [[ -n "$ACTIVE_JFR_CONTAINER_FILE" && -n "$ACTIVE_JFR_OUTPUT_FILE" && -n "$ACTIVE_JFR_LOG_FILE" ]]; then
        finish_jfr_recording "$ACTIVE_JFR_CONTAINER_FILE" "$ACTIVE_JFR_OUTPUT_FILE" "$ACTIVE_JFR_LOG_FILE"
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
    local system_stats_file="$run_folder/system-stats.csv"
    local system_stats_log_file="$run_folder/system-stats.log"
    local process_stats_file="$run_folder/process-stats.csv"
    local process_stats_log_file="$run_folder/process-stats.log"
    local funds_provisioning_log_file="$run_folder/provision-funds.log"
    local jfr_file="$run_folder/spi-load-test.jfr"
    local jfr_log_file="$run_folder/spi-jfr.log"
    local jfr_container_file="/tmp/spi-load-test-${timestamp}.jfr"
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

    if [[ "$ENABLE_SYSTEM_STATS_SAMPLING" == true ]]; then
        echo "Starting system stats sampler..."
        ./sample-system-stats.sh "$SYSTEM_STATS_SAMPLE_INTERVAL_SECONDS" "" "$system_stats_file" > "$system_stats_log_file" 2>&1 &
        SYSTEM_STATS_SAMPLER_PID=$!
        sleep 1

        if ! kill -0 "$SYSTEM_STATS_SAMPLER_PID" 2>/dev/null; then
            echo "System stats sampler failed to start. Log output:" >&2
            cat "$system_stats_log_file" >&2
            SYSTEM_STATS_SAMPLER_PID=""
            error_exit "System stats sampler failed"
        fi

    else
        echo "System stats sampler disabled."
    fi

    if [[ "$ENABLE_PROCESS_STATS_SAMPLING" == true ]]; then
        echo "Starting process stats sampler..."
        ./sample-process-stats.sh "$PROCESS_STATS_SAMPLE_INTERVAL_SECONDS" "" "$process_stats_file" "$PROCESS_STATS_TOP_N" > "$process_stats_log_file" 2>&1 &
        PROCESS_STATS_SAMPLER_PID=$!
        sleep 1

        if ! kill -0 "$PROCESS_STATS_SAMPLER_PID" 2>/dev/null; then
            echo "Process stats sampler failed to start. Log output:" >&2
            cat "$process_stats_log_file" >&2
            PROCESS_STATS_SAMPLER_PID=""
            error_exit "Process stats sampler failed"
        fi
    else
        echo "Process stats sampler disabled."
    fi

    if [[ "$ENABLE_JFR" == true ]]; then
        start_jfr_recording "$jfr_container_file" "$jfr_file" "$jfr_log_file"
    fi

    if [[ "$ENABLE_SYSTEM_STATS_SAMPLING" == true || "$ENABLE_PROCESS_STATS_SAMPLING" == true ]]; then
        echo "Collecting ${SYSTEM_STATS_PRE_TEST_SECONDS}s of pre-test host stats..."
        sleep "$SYSTEM_STATS_PRE_TEST_SECONDS"
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
        finish_jfr_recording "$jfr_container_file" "$jfr_file" "$jfr_log_file"
        stop_kafka_lag_sampler
        stop_system_stats_sampler
        stop_process_stats_sampler
        if [[ "$ENABLE_KAFKA_LAG_SAMPLING" == true ]]; then
            echo "Kafka lag samples saved to: $kafka_lag_file"
        fi
        if [[ "$ENABLE_SYSTEM_STATS_SAMPLING" == true ]]; then
            echo "System stats samples saved to: $system_stats_file"
        fi
        if [[ "$ENABLE_PROCESS_STATS_SAMPLING" == true ]]; then
            echo "Process stats samples saved to: $process_stats_file"
        fi
        if [[ "$ENABLE_JFR" == true ]]; then
            echo "SPI JFR log saved to: $jfr_log_file"
        fi
        echo "Console output saved to: $console_output_file"
        error_exit "k6 test execution failed"
    fi

    finish_jfr_recording "$jfr_container_file" "$jfr_file" "$jfr_log_file"
    stop_kafka_lag_sampler
    stop_system_stats_sampler
    stop_process_stats_sampler
    echo "Test completed successfully. Results saved to: $output_file"
    echo "Console output saved to: $console_output_file"
    if [[ "$ENABLE_KAFKA_LAG_SAMPLING" == true ]]; then
        echo "Kafka lag samples saved to: $kafka_lag_file"
    fi
    if [[ "$ENABLE_SYSTEM_STATS_SAMPLING" == true ]]; then
        echo "System stats samples saved to: $system_stats_file"
    fi
    if [[ "$ENABLE_PROCESS_STATS_SAMPLING" == true ]]; then
        echo "Process stats samples saved to: $process_stats_file"
    fi
    if [[ "$ENABLE_JFR" == true ]]; then
        echo "SPI JFR log saved to: $jfr_log_file"
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

    if [[ "$ENABLE_JFR" == true ]] && ! command -v docker &>/dev/null; then
        error_exit "'docker' command not found. It is required to record SPI JFR profiles."
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
    trap 'finish_active_jfr_recording; stop_kafka_lag_sampler; stop_system_stats_sampler; stop_process_stats_sampler' EXIT
    run_k6_test
}

main "$@"

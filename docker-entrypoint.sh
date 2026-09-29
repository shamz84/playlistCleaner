#!/bin/bash
set -e

# Docker entrypoint script for playlist processor
echo "🚀 Starting Playlist Processor Container"
echo "==========================================="

# Function to check if file exists and is readable
check_file() {
    local file=$1
    local description=$2
    
    if [ -f "$file" ]; then
        echo "✅ $description: $file"
        return 0
    else
        echo "⚠️  $description not found: $file"
        return 1
    fi
}

# Enhanced function to check file exists and has content
check_file_with_size() {
    local file=$1
    local description=$2
    local min_size=${3:-1}  # Default minimum size of 1 byte
    
    if [ -f "$file" ]; then
        local file_size=$(stat -c%s "$file" 2>/dev/null || echo "0")
        if [ "$file_size" -gt "$min_size" ]; then
            echo "✅ $description: $file (${file_size} bytes)"
            return 0
        else
            echo "❌ $description exists but is empty/too small: $file (${file_size} bytes)"
            return 1
        fi
    else
        echo "❌ $description not found: $file"
        return 1
    fi
}

# Function to validate JSON file content
validate_json_file() {
    local file=$1
    local description=$2
    
    if [ -f "$file" ]; then
        if python3 -c "import json; json.load(open('$file'))" 2>/dev/null; then
            echo "✅ $description: $file (valid JSON)"
            return 0
        else
            echo "❌ $description contains invalid JSON: $file"
            return 1
        fi
    else
        echo "❌ $description not found: $file"
        return 1
    fi
}

# Comprehensive input validation before starting process
validate_all_inputs() {
    echo -e "\n🔍 COMPREHENSIVE INPUT VALIDATION"
    echo "============================================="
    
    local validation_failed=false
    
    # 1. Check core Python scripts
    echo -e "\n📜 Core Scripts:"
    check_file "/app/process_playlist_complete_enhanced.py" "Main orchestrator script (enhanced)" || validation_failed=true
    check_file "/app/download_file.py" "Download script" || validation_failed=true
    check_file "/app/filter_m3u_with_auto_include.py" "Enhanced filter script" || validation_failed=true
    check_file "/app/replace_credentials_multi.py" "Credentials script" || validation_failed=true
    
    # 2. Check configuration files with content validation
    echo -e "\n⚙️  Configuration Files:"
    
    # Check if credentials.json exists and has valid content
    if [ -f "/app/credentials.json" ]; then
        if validate_json_file "/app/credentials.json" "Credentials configuration"; then
            # Validate credentials structure
            local cred_count=$(python3 -c "import json; data=json.load(open('/app/credentials.json')); print(len(data) if isinstance(data, list) else 0)" 2>/dev/null || echo "0")
            if [ "$cred_count" -gt 0 ]; then
                echo "✅ Found $cred_count credential set(s) in credentials.json"
            else
                echo "❌ credentials.json exists but contains no valid credential sets"
                validation_failed=true
            fi
        else
            validation_failed=true
        fi
    else
        echo "❌ credentials.json not found"
        validation_failed=true
    fi
    
    # Check group configuration
    if validate_json_file "/app/group_titles_with_flags.json" "Group filtering configuration"; then
        local group_count=$(python3 -c "import json; data=json.load(open('/app/group_titles_with_flags.json')); print(len([g for g in data if g.get('exclude') == 'false']))" 2>/dev/null || echo "0")
        if [ "$group_count" -gt 0 ]; then
            echo "✅ Found $group_count allowed groups in filtering configuration"
        else
            echo "❌ No allowed groups found in group_titles_with_flags.json"
            validation_failed=true
        fi
    else
        validation_failed=true
    fi
    
    # 3. Check step-specific requirements
    echo -e "\n📋 Step-specific Requirements:"
    
    # Download step validation
    if [[ "$SKIP_DOWNLOAD" != "--skip-download" ]]; then
        echo "🔍 Download step enabled - checking download requirements:"
        check_file "/app/download_file.py" "Download script" || validation_failed=true
        
        # Check if download config exists (optional)
        if [ -f "/app/download_config.json" ]; then
            validate_json_file "/app/download_config.json" "Download configuration (optional)" || echo "⚠️  Download config invalid, will use hardcoded values"
        fi
    else
        echo "⏭️  Download step skipped"
    fi
    
    # Filter step validation
    if [[ "$SKIP_FILTER" != "--skip-filter" ]]; then
        echo "🔍 Filter step enabled - checking filter requirements:"
        
        # Check for existing playlist files if download is skipped
        if [[ "$SKIP_DOWNLOAD" == "--skip-download" ]]; then
            local playlist_found=false
            if check_file_with_size "/app/data/downloaded_file.m3u" "Downloaded playlist file" 100; then
                playlist_found=true
            fi
            if check_file_with_size "/app/data/raw_playlist_AsiaUk.m3u" "Asia UK playlist file" 100; then
                playlist_found=true
            fi
            
            if [ "$playlist_found" = false ]; then
                echo "❌ No valid playlist files found for filtering (download is skipped)"
                validation_failed=true
            fi
        fi
        
        # Check Asia UK playlist (optional but recommended)
        if check_file_with_size "/app/data/raw_playlist_AsiaUk.m3u" "Asia UK playlist (optional)" 100; then
            echo "✅ Asia UK content will be included"
        else
            echo "⚠️  Asia UK playlist not found - Asia UK content will be skipped"
        fi
    else
        echo "⏭️  Filter step skipped"
    fi
    
    # Credentials step validation
    if [[ "$SKIP_CREDENTIALS" != "--skip-credentials" ]]; then
        echo "🔍 Credentials step enabled - checking credential requirements:"
        
        if [[ "$SKIP_FILTER" == "--skip-filter" ]]; then
            # When filter is skipped, we need downloaded file for credentials
            if [[ "$SKIP_DOWNLOAD" == "--skip-download" ]]; then
                # If download is also skipped, downloaded file must already exist
                if ! check_file_with_size "/app/data/downloaded_file.m3u" "Downloaded playlist (required when download and filter are skipped)" 1000; then
                    echo "❌ No downloaded playlist found for credential replacement (both download and filter are skipped)"
                    validation_failed=true
                fi
            else
                # If download is not skipped, downloaded file will be created during execution
                echo "✅ Downloaded playlist will be created during download step for credential replacement"
            fi
        else
            # When filter is not skipped, we need filtered playlist (will be created during filter step)
            echo "✅ Filtered playlist will be created during filter step for credential replacement"
        fi
    else
        echo "⏭️  Credentials step skipped"
    fi
    
    # Google Drive step validation
    if [[ "$SKIP_GDRIVE" != "--skip-gdrive" ]]; then
        echo "🔍 Google Drive step enabled - checking GDrive requirements:"
        if [ -f "/app/data/config/gdrive_config.json" ]; then
            validate_json_file "/app/data/config/gdrive_config.json" "Google Drive configuration" || validation_failed=true
        elif [ -f "/app/gdrive_config.json" ]; then
            validate_json_file "/app/gdrive_config.json" "Google Drive configuration" || validation_failed=true
            echo "💡 Consider moving gdrive_config.json to config folder for better organization"
        else
            echo "⚠️  Google Drive config not found - step may fail"
        fi
    else
        echo "⏭️  Google Drive step skipped"
    fi
    
    # 4. Check output directory permissions
    echo -e "\n📁 Output Directory:"
    mkdir -p /app/data
    if [ -w /app/data ]; then
        echo "✅ Output directory writable: /app/data"
    else
        echo "❌ Output directory not writable: /app/data"
        validation_failed=true
    fi
    
    # 5. Final validation result
    echo -e "\n🎯 VALIDATION SUMMARY:"
    echo "============================================="
    if [ "$validation_failed" = true ]; then
        echo "❌ INPUT VALIDATION FAILED!"
        echo "🛑 Cannot proceed - please fix the issues above"
        echo ""
        echo "💡 Common solutions:"
        echo "   - Ensure all config files are mounted to /app/data/config/"
        echo "   - Check that JSON files have valid syntax"
        echo "   - Verify credentials.json has at least one credential set"
        echo "   - Ensure playlist files exist if skipping download"
        echo "   - Check file permissions on mounted volumes"
        echo ""
        return 1
    else
        echo "✅ ALL INPUTS VALIDATED SUCCESSFULLY!"
        echo "🚀 Ready to start processing pipeline"
        echo ""
        return 0
    fi
}

# Display environment
echo "📋 Configuration:"
echo "   SKIP_API: ${SKIP_API:-'(not set)'}"
echo "   SKIP_DOWNLOAD: ${SKIP_DOWNLOAD:-'(not set)'}"
echo "   SKIP_FILTER: ${SKIP_FILTER:-'(not set)'}"
echo "   SKIP_UK_OVERRIDE: ${SKIP_UK_OVERRIDE:-'(not set)'}"
echo "   SKIP_CREDENTIALS: ${SKIP_CREDENTIALS:-'(not set)'}"
echo "   SKIP_GDRIVE: ${SKIP_GDRIVE:-'(not set)'}"

# Setup configuration files for runtime
setup_config_files() {
    echo -e "\n📁 Setting up configuration files:"
    
    # Function to setup config file with fallback
    setup_config_file() {
        local config_name=$1
        local description=$2
        
        # Check if config exists in mounted volume
        if [ -f "/app/data/config/$config_name" ]; then
            echo "✅ Using mounted $description: /app/data/config/$config_name"
            ln -sf "/app/data/config/$config_name" "/app/$config_name"
        elif [ -f "/app/$config_name" ]; then
            echo "✅ Using built-in $description: /app/$config_name"
        else
            echo "❌ $description not found: $config_name"
            return 1
        fi
        return 0
    }
    
    # Setup each config file
    setup_config_file "credentials.json" "Credentials configuration"
    setup_config_file "gdrive_config.json" "Google Drive configuration" || echo "⚠️  Google Drive config not found (optional)"
    setup_config_file "gdrive_credentials.json" "Google Drive credentials" || echo "⚠️  Google Drive credentials not found (optional)"
    setup_config_file "download_config.json" "Download configuration" || echo "⚠️  Download config not found (optional)"
    setup_config_file "group_titles_with_flags.json" "Group configuration"
    
    # Special handling for Google Drive token - copy read-only mount to writable location
    if [ -f "/app/gdrive_token.json" ]; then
        echo "✅ Found mounted Google Drive token, copying to writable location"
        cp "/app/gdrive_token.json" "/app/data/gdrive_token_writable.json"
        # Create symlink for backward compatibility
        ln -sf "/app/data/gdrive_token_writable.json" "/app/gdrive_token_writable.json"
        echo "✅ Google Drive token available at: /app/gdrive_token_writable.json"
    else
        echo "⚠️  Google Drive token not found (optional for Google Drive backup)"
    fi
    
    return 0
}

# Setup configuration files
setup_config_files

# Ensure output directory exists and is writable
echo -e "\n📁 Setting up output directory:"
mkdir -p /app/data
if [ -w /app/data ]; then
    echo "✅ Output directory: /app/data (writable)"
else
    echo "❌ Output directory: /app/data (not writable)"
    exit 1
fi

# Run comprehensive input validation before starting processing
echo -e "\n🔍 RUNNING COMPREHENSIVE INPUT VALIDATION"
echo "============================================="
if ! validate_all_inputs; then
    echo ""
    echo "💥 VALIDATION FAILED - ABORTING EXECUTION"
    echo "Please fix the validation errors above and try again."
    exit 1
fi

# Copy output files to data directory after processing
copy_outputs() {
    echo -e "\n📤 Copying outputs to /app/data:"
    
    # Copy generated playlists
    for file in /app/*.m3u; do
        if [ -f "$file" ] && [[ "$(basename "$file")" == 8k_* || "$(basename "$file")" == "filtered_playlist_final.m3u" || "$(basename "$file")" == "downloaded_file.m3u" ]]; then
            cp "$file" /app/data/ 2>/dev/null || true
            echo "   📄 $(basename "$file")"
        fi
    done
    
    # Copy any generated configuration files
    for file in gdrive_token.json download_results.json; do
        if [ -f "/app/$file" ]; then
            cp "/app/$file" /app/data/ 2>/dev/null || true
            echo "   🔧 $file"
        fi
    done
}

# Set up signal handlers for graceful shutdown
cleanup() {
    echo -e "\n🛑 Received signal, cleaning up..."
    copy_outputs
    exit 0
}

trap cleanup SIGTERM SIGINT

# Run the main script with arguments
echo -e "\n🚀 Starting enhanced playlist processing pipeline..."
echo "Command: python process_playlist_complete_enhanced.py $SKIP_API $SKIP_DOWNLOAD $SKIP_FILTER $SKIP_UK_OVERRIDE $SKIP_CREDENTIALS $SKIP_GDRIVE"

# Execute the main command
python process_playlist_complete_enhanced.py $SKIP_API $SKIP_DOWNLOAD $SKIP_FILTER $SKIP_UK_OVERRIDE $SKIP_CREDENTIALS $SKIP_GDRIVE

# Copy outputs after successful completion
copy_outputs

echo -e "\n🎉 Container execution completed successfully!"
echo "📁 Check /app/data for generated files"

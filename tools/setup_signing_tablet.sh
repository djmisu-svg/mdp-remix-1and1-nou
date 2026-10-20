#!/usr/bin/env bash
# Run on the owner's Android tablet in Termux. Never run with shell tracing.
set +x
set -euo pipefail
umask 077

repo='djmisu-svg/mdp-remix-1and1-nou'
branch='feature/premium-settings-share-updates'
environment='android-production'
key_directory="$HOME/.local/share/mdp-remix-signing"
keystore="$key_directory/mdp-remix-release.p12"
alias_name='mdp-remix-release'
backup_directory="$HOME/storage/downloads/MDP-release-signing-backup"
known_backup="$backup_directory/mdp-remix-release.p12"
existing=false
if [[ $# -gt 0 ]]; then
    if [[ $# -eq 1 && "$1" == '--status' ]]; then
        # Metadata only: no network, keytool, password prompt or filesystem writes.
        report_file() {
            if [[ -f "$2" ]]; then
                printf '%s=PRESENT\n' "$1"
            elif [[ -e "$2" || -L "$2" ]]; then
                printf '%s=NOT_A_REGULAR_FILE\n' "$1"
            else
                printf '%s=ABSENT\n' "$1"
            fi
        }
        report_file LOCAL_KEYSTORE "$keystore"
        report_file ENCRYPTED_DOWNLOADS_BACKUP "$known_backup"
        report_file PENDING_UPLOAD_MARKER "$key_directory/upload-pending.sha256"
        report_file CONFIGURED_CERTIFICATE_MARKER "$key_directory/configured-certificate.sha256"
        printf '%s\n' 'GITHUB_SECRETS=NOT_CHECKED' 'SIGNING_COMPATIBILITY=NOT_VERIFIED'
        exit 0
    fi
    if [[ $# -ne 2 || "$1" != '--existing' ]]; then
        echo 'Usage: bash setup_signing_tablet.sh [--status | --existing /path/to/original-keystore]'
        exit 1
    fi
    existing=true
    keystore="$2"
    [[ -f "$keystore" ]] || { echo 'The existing keystore file was not found.'; exit 1; }
fi
if [[ "$existing" == false && ! -f "$keystore" ]] &&
    [[ -e "$key_directory/upload-pending.sha256" || -e "$key_directory/configured-certificate.sha256" ||
       -e "$known_backup" || -L "$known_backup" || -e "$keystore" || -L "$keystore" ]]; then
    echo 'Stopped before key generation: an earlier signing setup or encrypted backup exists, but the local keystore is unavailable.'
    echo 'Recover and reuse that same keystore with --existing. Do not type NEW or remove signing markers to generate a replacement.'
    if [[ -f "$known_backup" ]]; then
        printf 'Reuse the encrypted backup: bash %q --existing %q\n' "$0" "$known_backup"
    fi
    exit 1
fi
for executable in keytool gh base64 sha256sum; do
    command -v "$executable" >/dev/null || { echo "Install the required Termux packages first; missing: $executable"; exit 1; }
done

temporary=$(mktemp -d)
cleanup() {
    rm -rf -- "$temporary"
    unset MDP_SETUP_STORE_PASSWORD MDP_SETUP_KEY_PASSWORD confirmation
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$key_directory"
chmod 700 "$key_directory"

gh auth status --hostname github.com >/dev/null 2>&1 || gh auth login --hostname github.com --web --git-protocol https
gh repo view "$repo" --json nameWithOwner >/dev/null
if gh api --include "repos/$repo/environments/$environment" \
    > "$temporary/environment-response" 2> "$temporary/environment-errors"; then
    : # Preserve the existing environment and all of its protection rules.
elif grep -Eq '^HTTP/[0-9.]+[[:space:]]+404([[:space:]]|$)' "$temporary/environment-response"; then
    printf '%s' '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' |
        gh api --method PUT "repos/$repo/environments/$environment" --input - >/dev/null
    for allowed_branch in "$branch" main; do
        gh api --method POST "repos/$repo/environments/$environment/deployment-branch-policies" \
            -f name="$allowed_branch" -f type=branch >/dev/null
    done
else
    echo 'Stopped: GitHub environment access could not be verified. Existing environment protections were not changed.'
    echo 'Check connectivity and repository-owner permissions, then rerun the same command.'
    exit 1
fi

# Only names are queried. GitHub never returns secret values.
gh secret list --repo "$repo" --env "$environment" --json name --jq '.[].name' > "$temporary/secret-names"
gh secret list --repo "$repo" --json name --jq '.[].name' >> "$temporary/secret-names"
if grep -Eq '^ANDROID_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD|SIGNING_CERT_SHA256)$' "$temporary/secret-names"; then
    if [[ ! -f "$key_directory/upload-pending.sha256" ]]; then
        echo 'Signing secrets already exist in the repository or android-production. They have been preserved.'
        echo 'Do not create a replacement key. Run the signing-audit workflow first; do not build or publish until identity and credentials are verified.'
        printf 'gh workflow run android.yml --repo %s --ref %s -f target=signing-audit\n' "$repo" "$branch"
        exit 0
    fi
fi

if [[ "$existing" == true ]]; then
    read -r -p 'Existing signing key alias: ' alias_name
elif [[ ! -f "$keystore" ]]; then
    echo 'A NEW key cannot update an older production app signed with another key.'
    echo 'Do not uninstall that app if its data must be retained. Use its original key instead.'
    read -r -p 'For a first public signing identity only, type NEW: ' confirmation
    [[ "$confirmation" == NEW ]] || { echo 'Stopped; no key generated.'; exit 1; }
fi
[[ -n "$alias_name" ]] || { echo 'A key alias is required.'; exit 1; }
read -r -s -p 'Keystore password (hidden; use a password manager): ' MDP_SETUP_STORE_PASSWORD
printf '\n'
if [[ "$existing" == true ]]; then
    read -r -s -p 'Private key password (hidden): ' MDP_SETUP_KEY_PASSWORD
    printf '\n'
else
    [[ ${#MDP_SETUP_STORE_PASSWORD} -ge 16 ]] || { echo 'Use a password of at least 16 characters.'; exit 1; }
    read -r -s -p 'Repeat the password: ' confirmation
    printf '\n'
    [[ "$confirmation" == "$MDP_SETUP_STORE_PASSWORD" ]] || { echo 'Passwords did not match.'; exit 1; }
    MDP_SETUP_KEY_PASSWORD="$MDP_SETUP_STORE_PASSWORD"
fi
export MDP_SETUP_STORE_PASSWORD MDP_SETUP_KEY_PASSWORD
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -alias "$alias_name" \
        -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
        -dname 'CN=MdP RemiX Android Release' \
        -storepass:env MDP_SETUP_STORE_PASSWORD -keypass:env MDP_SETUP_KEY_PASSWORD
    chmod 600 "$keystore"
fi
keytool -exportcert -keystore "$keystore" -alias "$alias_name" \
    -storepass:env MDP_SETUP_STORE_PASSWORD -file "$temporary/certificate.der" >/dev/null
keytool -certreq -keystore "$keystore" -alias "$alias_name" \
    -storepass:env MDP_SETUP_STORE_PASSWORD -keypass:env MDP_SETUP_KEY_PASSWORD \
    -file "$temporary/key-access.csr" >/dev/null
keytool -printcert -file "$temporary/certificate.der" > "$temporary/public-certificate.txt"
if grep -Eiq 'Android[[:space:]]+Debug' "$temporary/public-certificate.txt"; then
    echo 'A debug signing identity cannot be used for production.'
    exit 1
fi
certificate_sha=$(sha256sum "$temporary/certificate.der")
certificate_sha=${certificate_sha%% *}
for certificate_marker in "$key_directory/upload-pending.sha256" "$key_directory/configured-certificate.sha256"; do
    if [[ -f "$certificate_marker" ]] && [[ "$(cat "$certificate_marker")" != "$certificate_sha" ]]; then
        echo 'The saved signing setup belongs to another certificate. Nothing was overwritten.'
        exit 1
    fi
done

[[ -d "$HOME/storage/downloads" ]] || {
    echo 'Run termux-setup-storage, allow access, then run this script again. The encrypted key is preserved.'
    exit 1
}
mkdir -p "$backup_directory"
backup="$backup_directory/$(basename "$keystore")"
if [[ -e "$backup" ]]; then
    cmp -s "$keystore" "$backup" || { echo 'A different backup already exists. It was not overwritten.'; exit 1; }
else
    cp "$keystore" "$backup"
fi
cp "$temporary/certificate.der" "$backup_directory/signing-certificate.der"
printf '%s\n' "$certificate_sha" > "$backup_directory/signing-certificate-sha256.txt"
echo 'Encrypted keystore backup: Downloads / MDP-release-signing-backup'
echo 'Save this folder in private off-device storage and save the password separately in your password manager.'
read -r -p 'After saving both backups, type BACKEDUP: ' confirmation
[[ "$confirmation" == BACKEDUP ]] || { echo 'Stopped before uploading secrets; the local key and encrypted backup remain.'; exit 1; }

base64 < "$keystore" | tr -d '\n' > "$temporary/keystore.base64"
[[ $(wc -c < "$temporary/keystore.base64") -le 48000 ]] || { echo 'This keystore exceeds the GitHub secret size limit.'; exit 1; }
printf '%s\n' "$certificate_sha" > "$key_directory/upload-pending.sha256"
gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo" --env "$environment" < "$temporary/keystore.base64"
printf '%s' "$MDP_SETUP_STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo" --env "$environment"
printf '%s' "$alias_name" | gh secret set ANDROID_KEY_ALIAS --repo "$repo" --env "$environment"
printf '%s' "$MDP_SETUP_KEY_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$repo" --env "$environment"
printf '%s' "$certificate_sha" | gh secret set ANDROID_SIGNING_CERT_SHA256 --repo "$repo" --env "$environment"
mv "$key_directory/upload-pending.sha256" "$key_directory/configured-certificate.sha256"
echo 'Signing secrets configured. No build or publication was started.'
echo 'Next, audit credentials without building, then compare the certificate with the installed APK before any compatible-update claim:'
printf 'gh workflow run android.yml --repo %s --ref %s -f target=signing-audit\n' "$repo" "$branch"
